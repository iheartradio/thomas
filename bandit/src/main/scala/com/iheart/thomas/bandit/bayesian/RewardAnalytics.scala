package com.iheart.thomas.bandit.bayesian

import cats.FlatMap
import cats.implicits._
import com.iheart.thomas.analysis._
import com.iheart.thomas.bandit.`package`.ArmName

trait RewardAnalytics[F[_], R] {
  def sampleSize(r: R): Long
  def distribution(
      kpiName: KPIName,
      r: Map[ArmName, R],
      lastIteration: Option[List[ArmState[R]]]
    ): F[Map[ArmName, Probability]]
  def validateKPI(kpiName: KPIName): F[KPIDistribution]

}

object RewardAnalytics {
  implicit def metricDataConversions[F[_]: FlatMap](
      implicit kpiAPI: KPIDistributionApi[F],
      assessmentAlg: BasicAssessmentAlg[
        F,
        BetaKPIDistribution,
        Conversions
      ]
    ): RewardAnalytics[F, Conversions] =
    new RewardAnalytics[F, Conversions] {
      def sampleSize(r: Conversions): Long = r.total

      def distribution(
          kpiName: KPIName,
          r: Map[ArmName, Conversions],
          lastIteration: Option[List[ArmState[Conversions]]]
        ): F[Map[ArmName, Probability]] =
        kpiAPI
          .getSpecific[BetaKPIDistribution](
            kpiName
          )
          .flatMap { kpi =>
            def getPrior(armName: ArmName) =
              lastIteration
                .flatMap { le =>
                  le.find(_.name == armName)
                    .map(rs => kpi.updateFrom(rs.rewardState))
                }
                .getOrElse(kpi)

            assessmentAlg.assessOptimumGroup(
              r.map {
                case (armName, conversions) =>
                  (armName, (conversions, getPrior(armName)))
              }
            )

          }

      def validateKPI(kpiName: KPIName): F[KPIDistribution] =
        kpiAPI.getSpecific[BetaKPIDistribution](kpiName).widen

    }
}
