package com.iheart.thomas
package bandit
package bayesian
import java.time.{Instant, OffsetDateTime, ZoneOffset}

import cats.effect.Sync
import cats.Monoid
import cats.implicits._
import com.iheart.thomas.abtest.model.{Abtest, AbtestSpec, Group}
import com.iheart.thomas.analysis._
import com.stripe.rainier.sampler.RNG
import lihua.Entity
import henkan.convert.Syntax._

object ConversionBMABAlg {

  def default[F[_]](stateDao: BanditStateDAO[F, BayesianState[Conversions]],
                    kpiAPI: KPIApi[F],
                    abtestAPI: abtest.AbtestAlg[F])(implicit
                                                    sampleSettings: SampleSettings,
                                                    rng: RNG,
                                                    F: Sync[F]): ConversionBMABAlg[F] =
    new ConversionBMABAlg[F] {

      def updateRewardState(
          featureName: FeatureName,
          rewards: Map[ArmName, Conversions]): F[BayesianState[Conversions]] = {
        implicit val mc: Monoid[Conversions] = RewardState[Conversions]
        for {
          cs <- currentState(featureName)
          toUpdate = cs._2.updateArms(rewards)
          _ <- stateDao.upsert(toUpdate)
        } yield toUpdate
      }

      def init(banditSpec: BanditSpec,
               author: String,
               start: Instant): F[(Entity[Abtest], BayesianState[Conversions])] = {
        (abtestAPI
           .create(
             AbtestSpec(
               name = "Abtest for Bayesian MAB " + banditSpec.feature,
               feature = banditSpec.feature,
               author = author,
               start = start.atOffset(ZoneOffset.UTC),
               end = None,
               groups = banditSpec.arms.map(
                 Group(_, 1d / banditSpec.arms.size.toDouble)
               )
             ),
             false
           ),
         stateDao
           .upsert(
             BayesianState[Conversions](
               spec = banditSpec,
               arms = banditSpec.arms.map(
                 ArmState(_, RewardState[Conversions].empty, Probability(0d))),
               start = start
             )
           )).tupled
      }

      def reallocate(
          featureName: FeatureName,
          kpiName: KPIName): F[(Entity[Abtest], BayesianState[Conversions])] = {
        for {
          current <- currentState(featureName)
          kpi <- kpiAPI.getSpecific[BetaKPIDistribution](kpiName)
          (abtest, state) = current
          possibilities <- BetaKPIDistribution.basicAssessmentAlg
            .assessOptimumGroup(kpi, state.rewardState)
          abtest <- abtestAPI.continue(
            abtest.data
              .to[AbtestSpec]
              .set(start = OffsetDateTime.now, groups = abtest.data.groups.map { g =>
                g.copy(size = possibilities.get(g.name).fold(g.size)(identity))
              }))
        } yield (abtest, state)

      }

      def currentState(
          featureName: FeatureName): F[(Entity[Abtest], BayesianState[Conversions])] = {
        (abtestAPI
           .getTestsByFeature(featureName)
           .flatMap(_.headOption.liftTo[F](AbtestNotFound(featureName))),
         stateDao.get(featureName)).tupled
      }
    }
}
