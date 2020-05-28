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
      historical: Option[Map[ArmName, R]]
    ): F[Map[ArmName, Probability]]
  def validateKPI(kpiName: KPIName): F[KPIModel]

}

object RewardAnalytics {
  implicit def metricDataConversions[F[_]: FlatMap](
      implicit kpiAPI: KPIModelApi[F],
      assessmentAlg: BasicAssessmentAlg[
        F,
        BetaKPIModel,
        Conversions
      ]
    ): RewardAnalytics[F, Conversions] =
    new RewardAnalytics[F, Conversions] {
      def sampleSize(r: Conversions): Long = r.total

      def distribution(
          kpiName: KPIName,
          r: Map[ArmName, Conversions],
          historical: Option[Map[ArmName, Conversions]]
        ): F[Map[ArmName, Probability]] =
        kpiAPI
          .getSpecific[BetaKPIModel](
            kpiName
          )
          .flatMap { kpi =>
            def getPrior(armName: ArmName) =
              historical
                .flatMap { le =>
                  le.get(armName)
                    .map(rs => kpi.updateFrom(rs))
                }
                .getOrElse(kpi)

            assessmentAlg.assessOptimumGroup(
              r.map {
                case (armName, conversions) =>
                  (armName, (conversions, getPrior(armName)))
              }
            )

          }

      def validateKPI(kpiName: KPIName): F[KPIModel] =
        kpiAPI.getSpecific[BetaKPIModel](kpiName).widen

    }
}
