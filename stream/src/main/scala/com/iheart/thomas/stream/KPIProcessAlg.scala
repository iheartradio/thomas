package com.iheart.thomas.stream

import cats.{Foldable}
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import com.iheart.thomas.analysis.bayesian.Posterior
import com.iheart.thomas.{ArmName, FeatureName}
import com.iheart.thomas.analysis.monitor.ExperimentKPIStateDAO
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{ArmState, Key}
import com.iheart.thomas.analysis.{
  ConversionEvent,
  ConversionKPI,
  Conversions,
  KPIName,
  KPIRepo
}
import com.iheart.thomas.stream.JobSpec.ProcessSettings
import fs2.{Pipe, Stream}

trait KPIProcessAlg[F[_], Message] {
  def updatePrior(
      kpiName: KPIName,
      settings: ProcessSettings
    ): F[Pipe[F, Message, Unit]]

  def monitorTest(
      feature: FeatureName,
      kpiName: KPIName,
      settings: ProcessSettings
    ): F[Pipe[F, Message, Unit]]

}

object KPIProcessAlg {
  import JobAlg.chunkEvents

  def updateConversionArms[C[_]: Foldable](
      events: C[(ArmName, ConversionEvent)]
    )(existing: List[ArmState[Conversions]]
    ): List[ArmState[Conversions]] = {
    val newStats: Map[ArmName, Conversions] = events
      .foldMap { case (an, ce) => Map(an -> List(ce)) }
      .mapValues(Conversions(_))

    existing.map {
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
        if (existing.exists(_.name == name)) None
        else Some(ArmState(name, c, None))
    }
  }

  implicit def default[F[_]: Timer: Concurrent, Message](
      implicit
      convEventParser: KpiEventParser[F, Message, ConversionEvent],
      cKpiRepo: KPIRepo[F, ConversionKPI],
      cStateDAO: ExperimentKPIStateDAO[F, Conversions],
      armParser: ArmParser[F, Message]
    ): KPIProcessAlg[F, Message] =
    new KPIProcessAlg[F, Message] {

      def updatePrior(
          kpiName: KPIName,
          settings: ProcessSettings
        ): F[Pipe[F, Message, Unit]] =
        convEventParser(kpiName)
          .map {
            parser =>
              { (input: Stream[F, Message]) =>
                input
                  .evalMap(parser)
                  .filter(_.nonEmpty)
                  .through(
                    chunkEvents(settings)
                  )
                  .evalMap { chunk =>
                    cKpiRepo.update(kpiName) { k =>
                      k.copy(model = Posterior.update(k.model, Conversions(chunk)))
                    }
                  }
                  .void
              }
          }

      def monitorTest(
          feature: FeatureName,
          kpiName: KPIName,
          settings: ProcessSettings
        ): F[Pipe[F, Message, Unit]] =
        convEventParser(kpiName)
          .map {
            parser =>
              { (input: Stream[F, Message]) =>
                Stream.eval(cStateDAO.init(Key(feature, kpiName))) *>
                  input
                    .evalMap { m =>
                      armParser.parseArm(m, feature).flatMap { armO =>
                        armO.toList.flatTraverse { arm =>
                          parser(m).map(_.map((arm, _)))
                        }
                      }
                    }
                    .filter(_.nonEmpty)
                    .through(
                      chunkEvents(settings)
                    )
                    .evalMap { chunk =>
                      cStateDAO.update(Key(feature, kpiName))(
                        updateConversionArms(chunk)
                      )
                    }
                    .void
              }

          }

    }
}
