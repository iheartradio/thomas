package com.iheart.thomas
package analysis
package monitor
import bayesian._
import bayesian.models._
import cats.{Foldable, Monad, UnorderedFoldable}
import cats.effect.Timer
import cats.implicits._
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{ArmState, Key}
import com.stripe.rainier.sampler.{RNG, SamplerConfig}

trait MonitorAlg[F[_], K <: KPI] {
  type KS <: KPIStats
  type KPIEvent

  def updateState[C[_]: Foldable](
      key: ExperimentKPIState.Key,
      events: C[(ArmName, KPIEvent)]
    ): F[ExperimentKPIState[KS]]

  def initState(
      feature: FeatureName,
      kpi: KPIName
    ): F[ExperimentKPIState[KS]]

  def getState(key: Key): F[Option[ExperimentKPIState[KS]]]

  def resetState(key: Key): F[ExperimentKPIState[KS]]

  def getStates(
      feature: FeatureName,
      kpis: Seq[KPIName]
    ): F[Vector[ExperimentKPIState[KS]]]

  def allStates: F[Vector[ExperimentKPIState[KS]]]

  def evaluate(
      state: ExperimentKPIState[KS],
      benchmarkArm: Option[ArmName],
      includedArms: Option[Seq[ArmName]] = None
    ): F[List[Evaluation]]

  def updateModel[C[_]: UnorderedFoldable](
      name: KPIName,
      events: C[KPIEvent]
    ): F[K]

}

object MonitorAlg {

  def apply[F[_], K <: KPI](implicit inst: MonitorAlg[F, K]): MonitorAlg[F, K] =
    inst

  type MonitorConversionAlg[F[_]] = MonitorAlg[F, ConversionKPI]

  implicit def defaultConversions[F[_]: Monad](
      implicit cStateDAO: ExperimentKPIStateDAO[F, Conversions],
      cKpiRepo: KPIRepo[F, ConversionKPI],
      T: Timer[F]
    ): MonitorAlg[F, ConversionKPI] =
    new MonitorAlg[F, ConversionKPI] {
      type KS = Conversions
      type KPIEvent = ConversionEvent
      implicit val rng = RNG.default
      implicit val sc = SamplerConfig.default
      val evaluator = KPIEvaluator[F, BetaModel, Conversions]
      def evaluate(
          state: ExperimentKPIState[Conversions],
          benchmarkArm: Option[ArmName],
          includedArms: Option[Seq[ArmName]] = None
        ): F[List[Evaluation]] = {
        for {
          kpi <- cKpiRepo.get(state.key.kpi)
          r <-
            evaluator
              .evaluate(
                kpi.model,
                includedArms.fold(state.armsStateMap) { arms =>
                  state.armsStateMap.filterKeys(arms.toSet ++ benchmarkArm.toSet)
                },
                benchmarkArm.flatMap(ba => state.armsStateMap.get(ba).map((ba, _)))
              )
        } yield r
      }

      def resetState(
          key: Key
        ): F[ExperimentKPIState[KS]] =
        cStateDAO.remove(key) *> init(key)

      private def init(
          key: Key
        ): F[ExperimentKPIState[KS]] = {
        cStateDAO.ensure(key)(
          TimeUtil
            .now[F]
            .map(now => ExperimentKPIState[KS](key, Nil, now, now))
        )
      }

      def initState(
          feature: FeatureName,
          kpi: KPIName
        ): F[ExperimentKPIState[Conversions]] = init(Key(feature, kpi))

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

      def getState(key: Key): F[Option[ExperimentKPIState[Conversions]]] =
        cStateDAO.find(key)

      def allStates: F[Vector[ExperimentKPIState[Conversions]]] = cStateDAO.all

      def getStates(
          feature: FeatureName,
          kpis: Seq[KPIName]
        ): F[Vector[ExperimentKPIState[Conversions]]] =
        kpis.toVector.traverseFilter { kpi =>
          getState(Key(feature, kpi))
        }

      def updateModel[C[_]: UnorderedFoldable](
          name: KPIName,
          events: C[ConversionEvent]
        ): F[ConversionKPI] =
        cKpiRepo.update(name) { k =>
          k.copy(model = Posterior.update(k.model, Conversions(events)))
        }

    }
}
