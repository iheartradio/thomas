package com.iheart.thomas.stream

import cats.{Foldable, Monoid}
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import com.iheart.thomas.analysis.bayesian.Posterior
import com.iheart.thomas.{ArmName, FeatureName}
import com.iheart.thomas.analysis.monitor.ExperimentKPIStateDAO
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{ArmState, Key}
import com.iheart.thomas.analysis.{
  AccumulativeKPI,
  Aggregation,
  AllKPIRepo,
  ConversionEvent,
  ConversionKPI,
  Conversions,
  KPI,
  KPIName,
  KPIRepo,
  KPIStats
}
import com.iheart.thomas.stream.JobSpec.ProcessSettings
import fs2.{Pipe, Stream}

trait AllKPIProcessAlg[F[_], Message] {
  def updatePrior(
      kpiName: KPIName,
      settings: ProcessSettings
    ): F[Pipe[F, Message, Unit]]

  def monitorExperiment(
      feature: FeatureName,
      kpiName: KPIName,
      settings: ProcessSettings
    ): F[Pipe[F, Message, Unit]]

}

trait KPIProcessAlg[F[_], Message, K <: KPI] {
  def updatePrior(
      kpi: K,
      settings: ProcessSettings
    ): Pipe[F, Message, Unit]

  def monitorExperiment(
      kpi: K,
      feature: FeatureName,
      settings: ProcessSettings
    ): Pipe[F, Message, Unit]
}

object KPIProcessAlg {

  private[thomas] def updateConversionArms[C[_]: Foldable](
      events: C[(ArmName, ConversionEvent)]
    )(existing: List[ArmState[Conversions]]
    ): List[ArmState[Conversions]] = updateArms(events)(existing)

  private def updateArms[C[_]: Foldable, E, KS <: KPIStats](
      events: C[(ArmName, E)]
    )(existing: List[ArmState[KS]]
    )(implicit agg: Aggregation[E, KS],
      KS: Monoid[KS]
    ): List[ArmState[KS]] = {
    val newStats: Map[ArmName, KS] = events
      .foldMap { case (an, ce) => Map(an -> List(ce)) }
      .mapValues(agg(_))

    existing.map {
      case ArmState(armName, c, l) =>
        ArmState(
          armName,
          c |+| newStats.getOrElse(
            armName,
            KS.empty
          ),
          l
        )
    } ++ newStats.toList.mapFilter {
      case (name, c) =>
        if (existing.exists(_.name == name)) None
        else Some(ArmState(name, c, None))
    }
  }

  implicit def default[
      F[_]: Concurrent: Timer,
      K <: KPI,
      Message,
      Event,
      KS <: KPIStats
    ](implicit eventSource: KPIEventSource[
        F,
        K,
        Message,
        Event,
      ],
      cRepo: KPIRepo[F, K],
      posterior: Posterior[K, KS],
      agg: Aggregation[Event, KS],
      stateDAO: ExperimentKPIStateDAO[F, KS],
      KS: Monoid[KS]
    ): KPIProcessAlg[F, Message, K] =
    new KPIProcessAlg[F, Message, K] {

      def updatePrior(
          kpi: K,
          settings: ProcessSettings
        ): Pipe[F, Message, Unit] = { (input: Stream[F, Message]) =>
        input
          .through(eventSource.events(kpi).andThen(JobAlg.chunkEvents(settings)))
          .evalMap { chunk =>
            cRepo.update(kpi.name) { k =>
              posterior(k, agg(chunk))
            }
          }
          .void

      }

      def monitorExperiment(
          kpi: K,
          feature: FeatureName,
          settings: ProcessSettings
        ): Pipe[F, Message, Unit] = { (input: Stream[F, Message]) =>
        Stream.eval(stateDAO.init(Key(feature, kpi.name))) *>
          input
            .through(
              eventSource.events(kpi, feature) andThen JobAlg.chunkEvents(settings)
            )
            .evalMap { chunk =>
              stateDAO.update(Key(feature, kpi.name))(
                updateArms(chunk)
              )
            }
            .void

      }
    }
}

object AllKPIProcessAlg {

  implicit def default[F[_]: Timer: Concurrent, Message](
      implicit
      convProcessAlg: KPIProcessAlg[F, Message, ConversionKPI],
      accumProcessAlg: KPIProcessAlg[F, Message, AccumulativeKPI],
      allKPIRepo: AllKPIRepo[F]
    ): AllKPIProcessAlg[F, Message] =
    new AllKPIProcessAlg[F, Message] {

      def updatePrior(
          kpiName: KPIName,
          settings: ProcessSettings
        ): F[Pipe[F, Message, Unit]] =
        allKPIRepo.get(kpiName).map {
          case kpi: ConversionKPI   => convProcessAlg.updatePrior(kpi, settings)
          case kpi: AccumulativeKPI => accumProcessAlg.updatePrior(kpi, settings)
        }

      def monitorExperiment(
          feature: FeatureName,
          kpiName: KPIName,
          settings: ProcessSettings
        ): F[Pipe[F, Message, Unit]] =
        allKPIRepo.get(kpiName).map {
          case kpi: ConversionKPI =>
            convProcessAlg.monitorExperiment(kpi, feature, settings)
          case kpi: AccumulativeKPI =>
            accumProcessAlg.monitorExperiment(kpi, feature, settings)
        }

    }
}
