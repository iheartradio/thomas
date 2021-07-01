package com.iheart.thomas
package bandit.bayesian

import cats.FlatMap
import cats.implicits._
import com.iheart.thomas.analysis._
import com.iheart.thomas.analysis.bayesian.models.BetaModel
import com.iheart.thomas.analysis.bayesian._

trait RewardAnalyticsDepre[F[_], R] {
  def sampleSize(r: R): Long
  def distribution(
      kpiName: KPIName,
      r: Map[ArmName, R],
      historical: Option[Map[ArmName, R]]
    ): F[Map[ArmName, Probability]]
  def validateKPI(kpiName: KPIName): F[Unit]

}

object RewardAnalyticsDepre {
  implicit def metricDataConversions[F[_]: FlatMap](
      implicit kpiAlg: KPIRepo[F, ConversionKPI],
      evaluator: ModelEvaluator[
        F,
        BetaModel,
        Conversions
      ]
    ): RewardAnalyticsDepre[F, Conversions] =
    new RewardAnalyticsDepre[F, Conversions] {
      def sampleSize(r: Conversions): Long = r.total

      def distribution(
          kpiName: KPIName,
          r: Map[ArmName, Conversions],
          historical: Option[Map[ArmName, Conversions]]
        ): F[Map[ArmName, Probability]] =
        kpiAlg
          .get(
            kpiName
          )
          .flatMap { kpi =>
            def getPrior(armName: ArmName) =
              historical
                .flatMap { le =>
                  le.get(armName)
                    .map(rs => BetaModel(rs))
                }
                .getOrElse(kpi.model)

            evaluator.evaluate(
              r.map { case (armName, conversions) =>
                (armName, (conversions, getPrior(armName)))
              }
            )

          }

      def validateKPI(kpiName: KPIName): F[Unit] =
        kpiAlg.get(kpiName).void

    }
}
