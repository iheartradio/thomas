package com.iheart.thomas.analysis.bayesian

import cats.MonadThrow
import com.iheart.thomas.ArmName
import com.iheart.thomas.analysis.{
  AccumulativeKPI,
  AllKPIRepo,
  ConversionKPI,
  Conversions,
  KPIStats,
  PerUserSamplesLnSummary
}
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.Key
import com.iheart.thomas.analysis.monitor.{ExperimentKPIState, ExperimentKPIStateDAO}
import cats.syntax.all._
trait KPIEvaluator[F[_]] {
  def apply(
      stateKey: Key,
      benchmarkArm: Option[ArmName],
      includedArms: Option[Seq[ArmName]] = None
    ): F[Option[(List[Evaluation], ExperimentKPIState[KPIStats])]]

  def apply(
      state: ExperimentKPIState[KPIStats],
      includedArms: Option[Seq[ArmName]]
    ): F[List[Evaluation]]
}
object KPIEvaluator {

  implicit def default[F[_]: MonadThrow](
      implicit cStateDAO: ExperimentKPIStateDAO[F, Conversions],
      pStateDAO: ExperimentKPIStateDAO[F, PerUserSamplesLnSummary],
      kpiRepo: AllKPIRepo[F]
    ): KPIEvaluator[F] = new KPIEvaluator[F] {

    def evaluate[KS <: KPIStats, Model](
        state: ExperimentKPIState[KS],
        model: Model,
        benchmarkArm: Option[ArmName],
        includedArms: Option[Seq[ArmName]]
      )(implicit evaluator: ModelEvaluator[F, Model, KS]
      ): F[List[Evaluation]] =
      evaluator
        .evaluate(
          model,
          includedArms.fold(state.armsStateMap) { arms =>
            val keys = arms.toSet ++ benchmarkArm.toSet
            state.armsStateMap
              .filter { case (arm, _) => keys(arm) }
          },
          benchmarkArm
            .flatMap(ba => state.armsStateMap.get(ba).map((ba, _)))
        )

    def apply(
        stateKey: Key,
        benchmarkArm: Option[ArmName],
        includedArms: Option[Seq[ArmName]]
      ): F[Option[(List[Evaluation], ExperimentKPIState[KPIStats])]] = {

      kpiRepo
        .find(stateKey.kpi)
        .flatMap(_.traverseFilter {
          case ConversionKPI(_, _, _, model, _) =>
            cStateDAO
              .find(stateKey)
              .flatMap(
                _.traverse(s =>
                  evaluate(
                    s,
                    model,
                    benchmarkArm,
                    includedArms
                  ).map((_, s))
                )
              )
          case k: AccumulativeKPI =>
            pStateDAO
              .find(stateKey)
              .flatMap(
                _.traverse(s =>
                  evaluate(s, k.model, benchmarkArm, includedArms).map((_, s))
                )
              )
        })
    }

    def apply(
        s: ExperimentKPIState[KPIStats],
        includedArms: Option[Seq[ArmName]]
      ): F[List[Evaluation]] = kpiRepo
      .get(s.key.kpi)
      .flatMap {
        case c: ConversionKPI =>
          evaluate(
            s.asConversions.get,
            c.model,
            None,
            includedArms
          )
        case k: AccumulativeKPI =>
          evaluate(
            s.asPerUserSamplesLnSummary.get,
            k.model,
            None,
            includedArms
          )

      }

  }

}
