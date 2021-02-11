package com.iheart.thomas
package analysis
package monitor

import cats.{Foldable, Monad}
import cats.effect.Timer
import cats.implicits._
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{ArmState, Key}
import com.stripe.rainier.sampler.{RNG, SamplerConfig}

trait MonitorAlg[F[_]] {

  def updateState[C[_]: Foldable](
      key: ExperimentKPIState.Key,
      events: C[(ArmName, ConversionEvent)]
    ): F[ExperimentKPIState[Conversions]]

  def initConversion(
      feature: FeatureName,
      kpi: KPIName
    ): F[ExperimentKPIState[Conversions]]

  def getConversion(key: Key): F[Option[ExperimentKPIState[Conversions]]]

  def getConversions(
      feature: FeatureName,
      kpis: Seq[KPIName]
    ): F[Vector[ExperimentKPIState[Conversions]]]

  def evaluate(
      state: ExperimentKPIState[Conversions]
    ): F[Map[ArmName, Probability]]
}

object MonitorAlg {

  def apply[F[_]](implicit inst: MonitorAlg[F]): MonitorAlg[F] = inst

  implicit def default[F[_]: Monad](
      implicit cStateDAO: ExperimentKPIStateDAO[F, Conversions],
      cKPIAlg: ConversionKPIAlg[F],
      T: Timer[F]
    ): MonitorAlg[F] =
    new MonitorAlg[F] {
      implicit val rng = RNG.default
      implicit val sc = SamplerConfig.default
      val evaluator = KPIEvaluator[F, BetaModel, Conversions]
      def evaluate(
          state: ExperimentKPIState[Conversions]
        ): F[Map[ArmName, Probability]] =
        for {
          kpi <- cKPIAlg.get(state.key.kpi)
          r <-
            evaluator
              .evaluate(
                kpi.model,
                state.armsStateMap
              )
        } yield r

      private def init[R](
          feature: FeatureName,
          kpi: KPIName
        )(implicit dao: ExperimentKPIStateDAO[F, R]
        ): F[ExperimentKPIState[R]] = {
        val key = Key(feature, kpi)
        dao.ensure(key)(
          TimeUtil
            .now[F]
            .map(now => ExperimentKPIState[R](key, Nil, now))
        )
      }

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

      def getConversion(key: Key): F[Option[ExperimentKPIState[Conversions]]] =
        cStateDAO.find(key)

      def getConversions(
          feature: FeatureName,
          kpis: Seq[KPIName]
        ): F[Vector[ExperimentKPIState[Conversions]]] =
        kpis.toVector.traverseFilter { kpi =>
          getConversion(Key(feature, kpi))
        }

    }
}
