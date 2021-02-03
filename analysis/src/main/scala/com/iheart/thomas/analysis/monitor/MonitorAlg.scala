package com.iheart.thomas
package analysis
package monitor

import cats.{FlatMap, Foldable}
import cats.effect.Timer
import cats.implicits._
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{ArmState, Key}

trait MonitorAlg[F[_]] {

  def updateState[C[_]: Foldable](
      key: ExperimentKPIState.Key,
      events: C[(ArmName, ConversionEvent)]
    ): F[ExperimentKPIState[Conversions]]

  def initConversion(
      feature: FeatureName,
      kpi: KPIName
    ): F[ExperimentKPIState[Conversions]]

}

object MonitorAlg {

  def apply[F[_]](implicit inst: MonitorAlg[F]): MonitorAlg[F] = inst

  implicit def default[F[_]: FlatMap](
      implicit cStateDAO: ExperimentKPIStateDAO[F, Conversions],
      T: Timer[F]
    ): MonitorAlg[F] =
    new MonitorAlg[F] {

      private def init[R](
          feature: FeatureName,
          kpi: KPIName
        )(implicit dao: ExperimentKPIStateDAO[F, R]
        ): F[ExperimentKPIState[R]] =
        TimeUtil
          .now[F]
          .flatMap(now =>
            dao.insert(ExperimentKPIState[R](Key(feature, kpi), Nil, now))
          )

      def initConversion(
          feature: FeatureName,
          kpi: KPIName
        ): F[ExperimentKPIState[Conversions]] = init[Conversions](feature, kpi)

      def updateState[C[_]: Foldable](
          key: ExperimentKPIState.Key,
          events: C[(ArmName, ConversionEvent)]
        ): F[ExperimentKPIState[Conversions]] = {

        val newStats: Map[ArmName, Conversions] = events
          .foldMap { case (an, ce) => Map(an -> List(ce)) }
          .mapValues(Conversions(_))

        cStateDAO.updateState(key) { arms =>
          arms.map {
            case ArmState(armName, c, l) =>
              ArmState(
                armName,
                c |+| newStats.getOrElse(
                  armName,
                  Conversions.monoidInstance.empty
                ),
                l
              )
          } ++ newStats.toList.mapFilter {
            case (name, c) =>
              if (arms.exists(_.name == name)) None
              else Some(ArmState(name, c, None))
          }
        }
      }

    }
}
