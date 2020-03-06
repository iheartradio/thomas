package com.iheart.thomas
package bandit
package bayesian
import java.time.{Instant, OffsetDateTime, ZoneOffset}

import cats.Monoid
import cats.implicits._
import com.iheart.thomas.abtest.model.Abtest.Specialization
import com.iheart.thomas.abtest.model.{Abtest, AbtestSpec, Group, GroupSize}
import com.iheart.thomas.analysis._
import com.stripe.rainier.sampler.{RNG, Sampler}
import henkan.convert.Syntax._
import lihua.Entity
import tracking._

import scala.annotation.tailrec
object ConversionBMABAlg {

  implicit def default[F[_]](
      implicit
      stateDao: StateDAO[F, Conversions],
      settingsDao: BanditSettingsDAO[F, BanditSettings.Conversion],
      kpiAPI: KPIDistributionApi[F],
      abtestAPI: abtest.AbtestAlg[F],
      sampler: Sampler,
      rng: RNG,
      F: MonadThrowable[F],
      assessmentAlg: BasicAssessmentAlg[
        F,
        BetaKPIDistribution,
        Conversions
      ],
      nowF: F[Instant],
      log: EventLogger[F]
    ): ConversionBMABAlg[F] =
    new BayesianMABAlg[F, Conversions, BanditSettings.Conversion] {

      def updateRewardState(
          featureName: FeatureName,
          rewards: Map[ArmName, Conversions]
        ): F[BanditState[Conversions]] = {
        implicit val mc: Monoid[Conversions] =
          RewardState[Conversions]
        for {
          updated <- stateDao
            .updateArms(featureName, _.map { arm =>
              arm.copy(
                rewardState = rewards
                  .get(arm.name)
                  .fold(arm.rewardState)(arm.rewardState |+| _)
              )
            }.pure[F])
          _ <- log(Event.BanditKPIUpdate.Updated(updated))
        } yield updated
      }

      def init(banditSpec: ConversionBanditSpec): F[ConversionBandit] = {
        kpiAPI.getSpecific[BetaKPIDistribution](banditSpec.kpiName) >>
          (
            stateDao
              .insert(
                BanditState[Conversions](
                  feature = banditSpec.feature,
                  arms = banditSpec.arms.map(
                    ArmState(
                      _,
                      RewardState[Conversions].empty,
                      Probability(0d)
                    )
                  ),
                  start = banditSpec.start.toInstant,
                  version = 0L
                )
              ),
            settingsDao.insert(
              BanditSettings(
                feature = banditSpec.feature,
                title = banditSpec.title,
                author = banditSpec.author,
                kpiName = banditSpec.kpiName,
                minimumSizeChange = banditSpec.minimumSizeChange,
                initialSampleSize = banditSpec.initialSampleSize,
                distSpecificSettings = banditSpec.specificSettings
              )
            ),
            abtestAPI
              .create(
                AbtestSpec(
                  name = "Abtest for Bayesian MAB " + banditSpec.feature,
                  feature = banditSpec.feature,
                  author = banditSpec.author,
                  start = banditSpec.start,
                  end = None,
                  groups = banditSpec.arms.map(
                    Group(
                      _,
                      1d / banditSpec.arms.size.toDouble
                    )
                  ),
                  specialization = Some(Specialization.MultiArmBanditConversion)
                ),
                false
              )
          ).mapN((state, settings, a) => BayesianMAB(a, settings, state))
      }

      private def getConversionBandit(abtest: Entity[Abtest]): F[ConversionBandit] =
        (settingsDao.get(abtest.data.feature), stateDao.get(abtest.data.feature))
          .mapN(BayesianMAB(abtest, _, _))

      def getAll: F[Vector[ConversionBandit]] =
        findAll(None)

      def runningBandits(at: Option[OffsetDateTime]): F[Vector[ConversionBandit]] =
        nowF.flatMap { now =>
          findAll(at.orElse(Some(now.atOffset(ZoneOffset.UTC))))
        }

      def findAll(time: Option[OffsetDateTime]): F[Vector[ConversionBandit]] =
        abtestAPI //todo: this search depends how the bandit was initialized, if the abtest is created before the state, this will have concurrency problem.
          .getAllTestsBySpecialization(
            Specialization.MultiArmBanditConversion,
            time
          )
          .flatMap(_.traverse(getConversionBandit))

      def reallocate(feature: FeatureName): F[ConversionBandit] = {
        import Event.ConversionBanditReallocation._

        def resizeAbtest(bandit: ConversionBandit) = {
          val newGroups = allocateGroupSize(
            bandit.state.distribution,
            bandit.settings.minimumSizeChange
          )
          if (newGroups.toSet == bandit.abtest.data.groups.toSet)
            bandit.pure[F]
          else
            for {
              now <- nowF.map(_.atOffset(ZoneOffset.UTC))
              abtest <- abtestAPI.continue(
                bandit.abtest.data
                  .to[AbtestSpec]
                  .set(
                    start = now,
                    end = bandit.abtest.data.end.map(_.atOffset(ZoneOffset.UTC)),
                    groups = newGroups
                  )
              )
              _ <- bandit.settings.historyRetention.fold(F.unit)(
                before =>
                  abtestAPI
                    .cleanUp(
                      feature,
                      now
                        .minus(java.time.Duration.ofMillis(before.toMillis))
                    )
                    .void
              )
              _ <- log(Reallocated(abtest.data))
            } yield bandit.copy(abtest = abtest)
        }

        for {
          current <- currentState(feature)
          kpi <- kpiAPI.getSpecific[BetaKPIDistribution](
            current.settings.kpiName
          )
          _ <- log(Initiated(current.state))
          distribution <- assessmentAlg.assessOptimumGroup(
            kpi,
            current.state.rewardState
          )
          newState <- stateDao
            .updateArms(
              feature,
              _.map(
                arm =>
                  arm.copy(
                    likelihoodOptimum =
                      distribution.getOrElse(arm.name, arm.likelihoodOptimum)
                  )
              ).pure[F]
            )
          _ <- log(Calculated(newState))
          newBandit <- if (newState.arms.forall(
                             _.rewardState.total > current.settings.initialSampleSize
                           ))
            resizeAbtest(current.copy(state = newState))
          else
            F.pure(current.copy(state = newState))
        } yield newBandit

      }

      def delete(featureName: FeatureName): F[Unit] = {
        for {
          tests <- abtestAPI.getTestsByFeature(featureName)
          _ <- tests.headOption.fold(F.unit)(
            test => abtestAPI.terminate(test._id).void
          )
          _ <- stateDao.remove(featureName)
        } yield ()
      }

      def currentState(featureName: FeatureName): F[ConversionBandit] = {
        (
          abtestAPI
            .getTestsByFeature(featureName)
            .flatMap(
              _.headOption
                .liftTo[F](AbtestNotFound(featureName))
            ),
          settingsDao.get(featureName),
          stateDao.get(featureName)
        ).mapN(BayesianMAB.apply _)
      }
    }

  private[bayesian] def allocateGroupSize(
      optimalDistribution: Map[GroupName, Probability],
      precision: GroupSize
    ): List[Group] = {
    val sizeCandidates =
      0.to((1d / precision).toInt + 1)
        .toList
        .map(BigDecimal(_) * precision)
        .filter(_ < 1d) :+ BigDecimal(1)

    @tailrec
    def findClosest(
        v: BigDecimal,
        candidates: List[BigDecimal]
      ): BigDecimal = {
      candidates match {
        case last :: Nil =>
          last
        case head :: next :: tail =>
          val headDiff = (v - head).abs
          val nextDiff = (next - v).abs

          if (headDiff < nextDiff)
            head
          else
            findClosest(v, next :: tail)

        case Nil =>
          0d
      }
    }

    optimalDistribution.toList
      .foldLeft((sizeCandidates, List.empty[Group])) { (mp, gp) =>
        val (candidates, groups) = mp
        val (groupName, probability) = gp

        val size = findClosest(probability.p, candidates)
        val newGroups = groups :+ Group(groupName, size)
        val remainder = 1d - newGroups.foldMap(_.size)
        (
          candidates.filter(_ <= remainder),
          newGroups
        )
      }
      ._2

  }
}
