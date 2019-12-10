package com.iheart.thomas
package bandit
package bayesian
import java.time.{Instant, OffsetDateTime, ZoneOffset}

import cats.Monoid
import cats.implicits._
import com.iheart.thomas.abtest.model.Abtest.Specialization
import com.iheart.thomas.abtest.model.{AbtestSpec, Group}
import com.iheart.thomas.analysis._
import com.stripe.rainier.sampler.RNG
import henkan.convert.Syntax._
import tracking._

import scala.concurrent.duration.FiniteDuration
object ConversionBMABAlg {

  implicit def default[F[_]](
      implicit
      stateDao: BanditStateDAO[F, BanditState[Conversions]],
      kpiAPI: KPIApi[F],
      abtestAPI: abtest.AbtestAlg[F],
      sampleSettings: SampleSettings,
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
    new BayesianMABAlg[F, Conversions] {

      def updateRewardState(
          featureName: FeatureName,
          rewards: Map[ArmName, Conversions]
        ): F[BanditState[Conversions]] = {
        implicit val mc: Monoid[Conversions] =
          RewardState[Conversions]
        for {
          cs <- currentState(featureName)
          toUpdate = cs.state.updateArms(rewards)
          updated <- stateDao.upsert(toUpdate)
          _ <- log(Event.BanditKPIUpdated(updated))
        } yield updated
      }

      def init(banditSpec: BanditSpec): F[BayesianMAB[Conversions]] = {
        kpiAPI.getSpecific[BetaKPIDistribution](banditSpec.kpiName) >>
          (
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
              ),
            stateDao
              .upsert(
                BanditState[Conversions](
                  feature = banditSpec.feature,
                  title = banditSpec.title,
                  author = banditSpec.author,
                  arms = banditSpec.arms.map(
                    ArmState(
                      _,
                      RewardState[Conversions].empty,
                      Probability(0d)
                    )
                  ),
                  start = banditSpec.start.toInstant,
                  kpiName = banditSpec.kpiName
                )
              )
          ).mapN(BayesianMAB.apply _)
      }

      def getAll: F[Vector[BayesianMAB[Conversions]]] =
        findAll(None)

      def runningBandits(
          at: Option[OffsetDateTime]
        ): F[Vector[BayesianMAB[Conversions]]] =
        nowF.flatMap { now =>
          findAll(at.orElse(Some(now.atOffset(ZoneOffset.UTC))))
        }

      def findAll(
          time: Option[OffsetDateTime]
        ): F[Vector[BayesianMAB[Conversions]]] =
        abtestAPI
          .getAllTestsBySpecialization(
            Specialization.MultiArmBanditConversion,
            time
          )
          .flatMap(_.traverse { abtest =>
            stateDao
              .get(abtest.data.feature)
              .map(s => BayesianMAB(abtest, s))
          })

      def reallocate(
          featureName: FeatureName,
          cleanUpBefore: Option[FiniteDuration]
        ): F[BayesianMAB[Conversions]] = {
        import Event.ConversionBanditReallocation._
        for {
          current <- currentState(featureName)
          kpi <- kpiAPI.getSpecific[BetaKPIDistribution](
            current.state.kpiName
          )
          BayesianMAB(abtest, state) = current
          _ <- log(Initiated(state))
          possibilities <- assessmentAlg.assessOptimumGroup(
            kpi,
            state.rewardState
          )
          newState <- stateDao.upsert(
            state.copy(
              arms = state.arms.map(
                arm =>
                  arm.copy(
                    likelihoodOptimum =
                      possibilities.getOrElse(arm.name, arm.likelihoodOptimum)
                  )
              )
            )
          )
          _ <- log(Calculated(state))
          now <- nowF.map(_.atOffset(ZoneOffset.UTC))
          abtest <- abtestAPI.continue(
            abtest.data
              .to[AbtestSpec]
              .set(
                start = now,
                end = abtest.data.end.map(_.atOffset(ZoneOffset.UTC)),
                groups = abtest.data.groups.map { g =>
                  g.copy(
                    size = possibilities
                      .get(g.name)
                      .map(_.p)
                      .getOrElse(g.size)
                  )
                }
              )
          )
          _ <- cleanUpBefore.fold(F.unit)(
            before =>
              abtestAPI
                .cleanUp(
                  featureName,
                  now
                    .minus(java.time.Duration.ofMillis(before.toMillis))
                )
                .void
          )
          _ <- log(Reallocated(abtest.data))
        } yield BayesianMAB(abtest, newState)

      }

      def currentState(featureName: FeatureName): F[BayesianMAB[Conversions]] = {
        (
          abtestAPI
            .getTestsByFeature(featureName)
            .flatMap(
              _.headOption
                .liftTo[F](AbtestNotFound(featureName))
            ),
          stateDao.get(featureName)
        ).mapN(BayesianMAB.apply _)
      }
    }
}
