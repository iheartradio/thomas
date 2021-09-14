package com.iheart.thomas
package stream

import cats.data.NonEmptyList
import cats.{Foldable, Monoid}
import cats.effect.Temporal
import cats.implicits._
import com.iheart.thomas.analysis.bayesian.Posterior
import com.iheart.thomas.analysis.monitor.{ExperimentKPIState, ExperimentKPIStateDAO}
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{ArmState, ArmsState, Key, Specialization}
import com.iheart.thomas.analysis.{Aggregation, AllKPIRepo, ConversionKPI, KPI, KPIName, KPIRepo, KPIStats, QueryAccumulativeKPI}
import com.iheart.thomas.stream.JobSpec.ProcessSettings
import com.iheart.thomas.utils.time.Period
import fs2.{Pipe, Stream}

trait AllKPIProcessAlg[F[_], Message] {
  def updatePrior(
      kpiName: KPIName,
      settings: ProcessSettings
    ): F[Pipe[F, Message, Unit]]

  def monitorExperiment(
      feature: FeatureName,
      kpiName: KPIName,
      specialization: Specialization,
      settings: ProcessSettings
    ): F[Pipe[F, Message, ExperimentKPIState[KPIStats]]]

}

trait KPIProcessAlg[F[_], Message, K <: KPI] {
  def updatePrior(
      kpi: K,
      settings: ProcessSettings
    ): Pipe[F, Message, Unit]

  def monitorExperiment(
      kpi: K,
      feature: FeatureName,
      specialization: Specialization,
      settings: ProcessSettings
    ): Pipe[F, Message, ExperimentKPIState[KPIStats]]
}

object KPIProcessAlg {

  /** package private for testing purpose
    */
  private[thomas] def statsOf[C[_]: Foldable, E, KS <: KPIStats](
      events: C[ArmKPIEvents[E]]
    )(implicit agg: Aggregation[E, KS]
    ): Option[ArmsState[KS]] = {
    NonEmptyList.fromList(
      events
        .foldMap { ake => Map(ake.armName -> ake.es) }
        .toList
        .map { case (name, es) =>
          ArmState(name, agg(es), None)
        }
    )

  }

  private[thomas] def updateArms[KS <: KPIStats](
      newArmsState: ArmsState[KS],
      existing: ArmsState[KS]
    )(implicit
      KS: Monoid[KS]
    ): ArmsState[KS] = {
    existing.map { case ArmState(armName, c, l) =>
      ArmState(
        armName,
        c |+| newArmsState
          .find(_.name == armName)
          .map(_.kpiStats)
          .getOrElse(KS.empty),
        l
      )
    } ++ newArmsState.toList.mapFilter { as =>
      if (existing.exists(_.name == as.name)) None
      else Some(ArmState(as.name, as.kpiStats, None))
    }
  }

  implicit def default[
      F[_]: Temporal,
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
          specialization: Specialization,
          settings: ProcessSettings
        ): Pipe[F, Message, ExperimentKPIState[KPIStats]] = {
        (input: Stream[F, Message]) =>
          input
            .through(
              eventSource.events(kpi, feature) andThen JobAlg.chunkEvents(settings)
            )
            .evalMapFilter { chunk =>
              (
                statsOf(chunk),
                Period.of(chunk, (_: ArmKPIEvents[Event]).timeStamp)
              ).traverseN { (chunkStats, chunkPeriod) =>
                stateDAO.upsert(Key(feature, kpi.name, specialization)) {
                  (existing, existingPeriod) =>
                    (
                      updateArms(chunkStats, existing),
                      chunkPeriod |+| existingPeriod
                    )
                }((chunkStats, chunkPeriod))
              }

            }

      }
    }
}

object AllKPIProcessAlg {

  implicit def default[F[_]: Temporal, Message](
      implicit
      convProcessAlg: KPIProcessAlg[F, Message, ConversionKPI],
      accumProcessAlg: KPIProcessAlg[F, Message, QueryAccumulativeKPI],
      allKPIRepo: AllKPIRepo[F]
    ): AllKPIProcessAlg[F, Message] =
    new AllKPIProcessAlg[F, Message] {

      def updatePrior(
          kpiName: KPIName,
          settings: ProcessSettings
        ): F[Pipe[F, Message, Unit]] =
        allKPIRepo.get(kpiName).map {
          case kpi: ConversionKPI => convProcessAlg.updatePrior(kpi, settings)
          case kpi: QueryAccumulativeKPI =>
            accumProcessAlg.updatePrior(kpi, settings)
        }

      def monitorExperiment(
          feature: FeatureName,
          kpiName: KPIName,
          specialization: Specialization,
          settings: ProcessSettings
        ): F[Pipe[F, Message, ExperimentKPIState[KPIStats]]] =
        allKPIRepo.get(kpiName).map {
          case kpi: ConversionKPI =>
            convProcessAlg.monitorExperiment(kpi, feature, specialization, settings)
          case kpi: QueryAccumulativeKPI =>
            accumProcessAlg.monitorExperiment(kpi, feature, specialization, settings)
        }

    }
}
