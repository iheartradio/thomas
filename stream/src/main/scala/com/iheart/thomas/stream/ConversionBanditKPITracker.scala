package com.iheart.thomas.stream

import cats.effect.ConcurrentEffect
import fs2.{Pipe, Stream}
import ConversionBanditKPITracker._
import com.iheart.thomas.FeatureName
import com.iheart.thomas.analysis.{Conversions, KPIName}
import cats.implicits._
import com.iheart.thomas.bandit.`package`.ArmName
import com.iheart.thomas.bandit.bayesian.{BayesianMAB, ConversionBMABAlg}
import com.iheart.thomas.bandit.tracking.EventLogger
import cats.effect.Timer

class ConversionBanditKPITracker[F[_]: Timer](
    bandits: Vector[BayesianMAB[Conversions]]
  )(implicit
    bmabAlg: ConversionBMABAlg[F],
    log: EventLogger[F],
    F: ConcurrentEffect[F]) {

  def updateAllConversions[I](
      chunkSize: Int,
      toEvent: (FeatureName, KPIName) => F[Pipe[F, I, (ArmName, ConversionEvent)]]
    ): Pipe[F, I, Unit] = {
    def updateConversion(
        featureName: FeatureName
      ): Pipe[F, (ArmName, ConversionEvent), Unit] =
      ConversionBanditKPITracker.toConversion(chunkSize) andThen { input =>
        input.evalMap { r =>
          log.debug(s"Updating reward $r to bandit $featureName") *>
            bmabAlg
              .updateRewardState(featureName, r)
              .void
        }
      }

    val updatePipes =
      log.debug(s"updating KPI state for ${bandits.map(_.feature)}") *>
        bandits
          .traverse { b =>
            toEvent(b.feature, b.kpiName)
              .map(_ andThen updateConversion(b.feature))
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

object ConversionBanditKPITracker {
  type ConversionEvent = Boolean
  val Converted = true
  val Viewed = false

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
