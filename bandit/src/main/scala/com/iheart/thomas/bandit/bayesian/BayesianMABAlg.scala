package com.iheart.thomas
package bandit
package bayesian

import cats.effect.Clock
import cats.implicits._
import cats.MonadThrow
import com.iheart.thomas.abtest.model.Abtest.Specialization
import com.iheart.thomas.abtest.model.{
  Abtest,
  AbtestSpec,
  Group,
  GroupRange,
  GroupSize,
  Tag,
  UserMetaCriteria
}
import com.iheart.thomas.analysis.bayesian.KPIEvaluator
import com.iheart.thomas.analysis.monitor.{
  AllExperimentKPIStateRepo,
  ExperimentKPIHistoryRepo,
  ExperimentKPIState
}
import com.iheart.thomas.analysis.{AllKPIRepo, KPIStats, Probability}
import com.iheart.thomas.bandit.bayesian.BayesianMABAlg.BanditAbtestSpec
import com.iheart.thomas.bandit.tracking.BanditEvent
import com.iheart.thomas.tracking.EventLogger
import com.iheart.thomas.utils.time.now
import lihua.Entity
import utils.time._

import java.time.{OffsetDateTime, ZoneOffset}
import scala.annotation.tailrec

/** Abtest based Bayesian Multi Arm Bandit Algebra
  */
trait BayesianMABAlg[F[_]] {
  type Bandit = BayesianMAB
  def init(banditSpec: BanditSpec): F[Bandit]

  def get(featureName: FeatureName): F[Bandit]

  def getAll: F[Vector[Bandit]]

  def updatePolicy(state: ExperimentKPIState[KPIStats]): F[Bandit]

  def delete(featureName: FeatureName): F[Unit]

  def resetState(featureName: FeatureName): F[Bandit]

  def update(banditSpec: BanditSpec, bas: BanditAbtestSpec): F[BanditSpec]

}

object BayesianMABAlg {

  case class BanditAbtestSpec(
      requiredTags: List[Tag] = Nil,
      userMetaCriteria: UserMetaCriteria = None,
      segmentRanges: List[GroupRange] = Nil)

  private[bayesian] def createTestSpec[F[_]: MonadThrow](
      from: BanditSpec,
      start: OffsetDateTime
    ): F[AbtestSpec] = {
    val initialSize = if (from.arms.exists(_.initialSize.isEmpty)) {
      (1d - from.arms
        .flatMap(_.initialSize)
        .sum) / from.arms.count(_.initialSize.isEmpty).toDouble
    } else BigDecimal(0)

    AbtestSpec(
      name = "Abtest for Bayesian MAB " + from.feature,
      feature = from.feature,
      author = from.author,
      start = start,
      end = None,
      groups = from.arms
        .map(as => Group(as.name, as.initialSize.getOrElse(initialSize), as.meta))
        .toList,
      specialization = Some(Specialization.MultiArmBandit)
    ).pure[F]
  }

  implicit def apply[F[_]](
      implicit stateDao: AllExperimentKPIStateRepo[F],
      log: EventLogger[F],
      specDao: BanditSpecDAO[F],
      kpiEvaluator: KPIEvaluator[F],
      kpiRepo: AllKPIRepo[F],
      abtestAPI: abtest.AbtestAlg[F],
      T: Clock[F],
      F: MonadThrow[F],
      kpiHistoryRepo: ExperimentKPIHistoryRepo[F]
    ): BayesianMABAlg[F] =
    new BayesianMABAlg[F] {

      def getAll: F[Vector[Bandit]] =
        now[F].flatMap { nowT =>
          findAll(Some(nowT.atOffset(ZoneOffset.UTC)))
        }

      def findAll(time: Option[OffsetDateTime]): F[Vector[Bandit]] = {
        def getBandit(abtest: Entity[Abtest]): F[Bandit] =
          for {
            settings <- specDao.get(abtest.data.feature)
            state <- stateDao.find(settings.stateKey)
          } yield BayesianMAB(abtest, settings, state)

        abtestAPI // todo: this search depends how the bandit was initialized, if the abtest is created before the state, this will have concurrency problem.
          .getAllTestsBySpecialization(
            Specialization.MultiArmBandit,
            time
          )
          .flatMap(_.traverse(getBandit))
      }

      def delete(featureName: FeatureName): F[Unit] =
        specDao.get(featureName).flatMap { bs =>
          (
            abtestAPI.getTestsByFeature(featureName).flatMap { tests =>
              tests.headOption.fold(F.unit)(test =>
                abtestAPI.terminate(test._id).void
              )
            },
            specDao.remove(featureName),
            stateDao.delete(bs.stateKey),
            kpiHistoryRepo.delete(bs.stateKey)
          ).tupled.void
        }

      def resetState(featureName: FeatureName): F[Bandit] =
        get(featureName)
          .flatTap { b =>
            stateDao.delete(b.spec.stateKey)
          }
          .map(_.copy(state = None))

      def abtest(featureName: FeatureName): F[Entity[Abtest]] =
        abtestAPI
          .getTestsByFeature(featureName)
          .flatMap(
            _.headOption
              .liftTo[F](AbtestNotFound(featureName))
          )

      def get(featureName: FeatureName): F[Bandit] =
        specDao.get(featureName).flatMap { bs =>
          (abtest(featureName), stateDao.find(bs.stateKey))
            .mapN(BayesianMAB(_, bs, _))
        }

      def update(banditSpec: BanditSpec, bas: BanditAbtestSpec): F[BanditSpec] = {
        for {
          now <- now[F]
          test <- abtest(banditSpec.feature)
          spec = test.data.toSpec
          _ <- abtestAPI.continue(
            spec.copy(
              start = now.toOffsetDateTimeSystemDefault,
              requiredTags = bas.requiredTags,
              userMetaCriteria = bas.userMetaCriteria,
              segmentRanges = bas.segmentRanges
            )
          )
          r <- specDao.update(banditSpec)
        } yield r

      }

      def init(banditSpec: BanditSpec): F[Bandit] =
        now[F].flatMap { start =>
          kpiRepo.get(banditSpec.kpiName) >>
            (
              specDao.insert(banditSpec),
              createTestSpec[F](banditSpec, start.toOffsetDateTimeSystemDefault)
                .flatMap(
                  abtestAPI.create(_, auto = false)
                )
            ).mapN((settings, a) => BayesianMAB(a, settings, None))
              .onError { case _ =>
                delete(banditSpec.feature)
              }
        }

      def updatePolicy(state: ExperimentKPIState[KPIStats]): F[Bandit] = {
        import BanditEvent.BanditPolicyUpdate._

        def resizeAbtest(
            abtest: Entity[Abtest],
            state: ExperimentKPIState[_],
            settings: BanditSpec
          ) = {

          val reservedGroups = abtest.data.groups
            .filter(g => settings.reservedGroups.contains(g.name))

          val newGroups = allocateGroupSize(
            state.distribution,
            settings.minimumSizeChange,
            availableSize = BigDecimal(1) - reservedGroups.map(_.size).sum
          ) ++ reservedGroups
          if (newGroups.toSet == abtest.data.groups.toSet)
            abtest.pure[F]
          else
            for {
              nowT <- now[F].map(_.atOffset(ZoneOffset.UTC))
              updated <- abtestAPI.continue(
                abtest.data
                  .copy(groups = newGroups)
                  .toSpec
                  .copy(
                    start = nowT,
                    end = abtest.data.end.map(_.atOffset(ZoneOffset.UTC))
                  )
              )
              _ <- settings.historyRetention.fold(F.unit)(before =>
                abtestAPI
                  .cleanUp(
                    abtest.data.feature,
                    nowT
                      .minus(java.time.Duration.ofMillis(before.toMillis))
                  )
                  .void
              )
              _ <- log(Reallocated(updated.data))
            } yield updated
        }

        for {
          settings <- specDao.get(state.key.feature)
          evaluation <- kpiEvaluator(
            state,
            Some(state.arms.map(_.name).filterNot(settings.reservedGroups))
          )
          newState <-
            stateDao
              .updateOptimumLikelihood(
                settings.stateKey,
                evaluation.map(e => (e.name, e.probabilityBeingOptimal)).toMap
              )
          _ <- log(Calculated(newState))
          hasEnoughSamples =
            newState.arms
              .forall { r =>
                r.sampleSize > settings.initialSampleSize
              }

          currentTest <- abtest(settings.feature)
          updatedTest <-
            if (hasEnoughSamples)
              resizeAbtest(currentTest, newState, settings) <*
                kpiHistoryRepo.append(newState)
            else
              F.pure(currentTest)

        } yield BayesianMAB(updatedTest, settings, state = Some(newState))

      }

    }

  private[bayesian] def allocateGroupSize(
      optimalDistribution: Map[GroupName, Probability],
      precision: GroupSize,
      availableSize: BigDecimal
    ): List[Group] = {
    assert(availableSize <= BigDecimal(1))
    val sizeCandidates =
      0.to((availableSize / precision).toInt + 1)
        .toList
        .map(BigDecimal(_) * precision)
        .filter(_ < availableSize) :+ availableSize

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
      .sortBy(_._2.p)
      .foldLeft((sizeCandidates, List.empty[Group])) { (mp, gp) =>
        val (candidates, groups) = mp
        val (groupName, probability) = gp
        val sizeFromOptimalLikelyHood = probability.p * availableSize
        val size = findClosest(sizeFromOptimalLikelyHood, candidates)
        val newGroups = groups :+ Group(groupName, size, None)
        val remainder = availableSize - newGroups.foldMap(_.size)
        (
          candidates.filter(_ <= remainder),
          newGroups
        )
      }
      ._2

  }
}
