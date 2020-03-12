package com.iheart.thomas.stream

import cats.effect.ConcurrentEffect
import fs2.{Pipe, Stream}
import com.iheart.thomas.FeatureName
import com.iheart.thomas.analysis.{Conversions, KPIName}
import cats.implicits._
import com.iheart.thomas.bandit.`package`.ArmName
import com.iheart.thomas.bandit.bayesian._

import com.iheart.thomas.bandit.tracking.{Event, EventLogger}
import cats.effect.Timer

import scala.concurrent.duration.FiniteDuration

object ConversionBanditUpdater {
  type ConversionEvent = Boolean
  val Converted = true
  val Viewed = false

  type Settings = BanditSettings[BanditSettings.Conversion]

  private[stream] def runningBandits[F[_]: Timer: ConcurrentEffect](
      allowedBanditsStaleness: FiniteDuration
    )(implicit
      cbm: ConversionBMABAlg[F],
      log: EventLogger[F]
    ): Stream[F, ConversionBandits] =
    ((Stream.emit[F, Unit](()) ++ Stream
      .fixedDelay[F](allowedBanditsStaleness))
      .evalMap(_ => cbm.runningBandits()))
      .scan(
        (
          Vector.empty[ConversionBandit],
          none[ConversionBandits]
        )
      ) { (memo, current) =>
        val old = memo._1

        def banditIdentifier(b: BayesianMAB[_, _]) =
          (b.abtest.data.groups.map(_.name).toSet, b.settings)

        (
          current,
          if (current.map(banditIdentifier).toSet == old.map(banditIdentifier).toSet)
            None
          else Some(current)
        )
      }
      .mapFilter(_._2)
      .evalTap(
        b =>
          log(
            Event.BanditKPIUpdate
              .NewSetOfRunningBanditsDetected(b.map(_.feature))
          )
      )

  def updatePipes[F[_]: Timer: ConcurrentEffect, I](
      name: String,
      allowedBanditsStaleness: FiniteDuration,
      toEvent: (FeatureName, KPIName) => F[Pipe[F, I, (ArmName, ConversionEvent)]]
    )(implicit
      cbm: ConversionBMABAlg[F],
      log: EventLogger[F]
    ): Stream[F, Pipe[F, I, Unit]] = {

    def updateConversion(
        settings: Settings
      ): Pipe[F, (ArmName, ConversionEvent), Unit] =
      toConversion[F](settings.distSpecificSettings.eventChunkSize) andThen {
        _.broadcastTo[F](
          (i: Stream[F, Map[ArmName, Conversions]]) =>
            i.evalMap { r =>
              log.debug(
                s"Updating reward $r to bandit ${settings.feature} by $name"
              ) *>
                cbm
                  .updateRewardState(settings.feature, r)
                  .void
            },
          (i: Stream[F, Map[ArmName, Conversions]]) =>
            i.chunkN(settings.distSpecificSettings.reallocateEveryNChunk).evalMap {
              _ =>
                cbm.reallocate(settings.feature).void
            }
        )
      }

    runningBandits(allowedBanditsStaleness).map { bandits =>
      val updatePipes =
        log.debug(s"updating KPI state for ${bandits.map(_.feature)}") *>
          bandits
            .traverse { b =>
              toEvent(b.feature, b.kpiName)
                .map(_ andThen updateConversion(b.settings))
            }

      { input: Stream[F, I] =>
        Stream
          .eval(updatePipes)
          .flatMap { featurePipes =>
            if (featurePipes.nonEmpty)
              input.broadcastThrough(featurePipes: _*)
            else
              input.void
          }
      }
    }
  }

  private[stream] def toConversion[F[_]](
      chunkSize: Int
    ): Pipe[F, (ArmName, ConversionEvent), Map[
    ArmName,
    Conversions
  ]] = { input =>
    input
      .chunkN(chunkSize, true)
      .map { chunk =>
        val isConverted = identity[ConversionEvent] _
        (chunk
          .foldMap {
            case (an, ce) =>
              Map(an -> List(ce))
          })
          .map {
            case (an, ces) =>
              val convertedCount = ces.count(isConverted)
              (
                an,
                Conversions(
                  converted = convertedCount.toLong,
                  total = ces.size.toLong
                )
              )
          }
      }
  }
}
