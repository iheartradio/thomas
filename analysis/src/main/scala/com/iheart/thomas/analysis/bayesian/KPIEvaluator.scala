package com.iheart.thomas.analysis.bayesian

import cats.MonadThrow
import com.iheart.thomas.ArmName
import com.iheart.thomas.analysis.{
  AccumulativeKPI,
  AllKPIRepo,
  ConversionKPI,
  Conversions,
  KPIStats,
  PerUserSamplesSummary
}
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.Key
import com.iheart.thomas.analysis.monitor.{ExperimentKPIState, ExperimentKPIStateDAO}
import cats.implicits._
trait KPIEvaluator[F[_]] {
  def apply(
      stateKey: Key,
      benchmarkArm: Option[ArmName],
      includedArms: Option[Seq[ArmName]] = None
    ): F[Option[(List[Evaluation], ExperimentKPIState[KPIStats])]]

}
object KPIEvaluator {

  implicit def default[F[_]: MonadThrow](
      implicit cStateDAO: ExperimentKPIStateDAO[F, Conversions],
      pStateDAO: ExperimentKPIStateDAO[F, PerUserSamplesSummary],
      kpiRepo: AllKPIRepo[F]
    ): KPIEvaluator[F] =
    (
        stateKey: Key,
        benchmarkArm: Option[ArmName],
        includedArms: Option[Seq[ArmName]]
    ) => {

      def evaluate[KS <: KPIStats, Model](
          stateO: Option[ExperimentKPIState[KS]],
          model: Model
        )(implicit evaluator: ModelEvaluator[F, Model, KS]
        ): F[Option[(List[Evaluation], ExperimentKPIState[KS])]] =
        stateO.traverse(state =>
          evaluator
            .evaluate(
              model,
              includedArms.fold(state.armsStateMap) { arms =>
                state.armsStateMap
                  .filterKeys(arms.toSet ++ benchmarkArm.toSet)
              },
              benchmarkArm
                .flatMap(ba => state.armsStateMap.get(ba).map((ba, _)))
            )
            .map((_, state))
        )

      kpiRepo
        .find(stateKey.kpi)
        .flatMap(_.traverseFilter {
          case ConversionKPI(_, _, _, model, _) =>
            cStateDAO
              .find(stateKey)
              .flatMap(s => evaluate(s, model).widen)
          case AccumulativeKPI(_, _, _, model, _, _) =>
            pStateDAO
              .find(stateKey)
              .flatMap(s => evaluate(s, model).widen)
        })
    }

}
