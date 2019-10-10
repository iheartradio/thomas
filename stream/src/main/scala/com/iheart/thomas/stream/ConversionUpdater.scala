package com.iheart.thomas.stream

import cats.effect.{ConcurrentEffect, Resource}
import com.iheart.thomas.client.BayesianBanditClient
import fs2.{Pipe, Stream}
import ConversionUpdater._
import com.iheart.thomas.FeatureName
import com.iheart.thomas.analysis.Conversions
import cats.implicits._
import com.iheart.thomas.bandit.`package`.ArmName
import com.iheart.thomas.bandit.bayesian.BayesianMABAlg
import io.chrisdavenport.log4cats.Logger
import scala.concurrent.ExecutionContext

class ConversionUpdater[F[_]: ConcurrentEffect](
    implicit
    client: BayesianMABAlg[F, Conversions],
    logger: Logger[F]) {

  def updateAllConversions[I](
      chunkSize: Int,
      toEvent: FeatureName => F[Pipe[F, I, (ArmName, ConversionEvent)]]
    ): F[Pipe[F, I, Unit]] = {
    def updateConversion(
        featureName: FeatureName
      ): Pipe[F, (ArmName, ConversionEvent), Unit] =
      ConversionUpdater.toConversion(chunkSize) andThen { input =>
        input.evalMap { r =>
          client
            .updateRewardState(featureName, r)
            .void <* logger.debug(
            s"Conversion updated for $featureName $r"
          )
        }
      }

    client.runningBandits(None).flatMap { bandits =>
      bandits
        .traverse { b =>
          val feature = b.abtest.data.feature
          toEvent(feature).map(_ andThen updateConversion(feature))
        }
        .map { featurePipes => input: Stream[F, I] =>
          input.broadcastThrough(featurePipes: _*)
        }
    }
  }

}

object ConversionUpdater {
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

  def resource[F[_]: ConcurrentEffect](
      rootUrl: String
    )(implicit ec: ExecutionContext,
      logger: Logger[F]
    ): Resource[F, ConversionUpdater[F]] =
    BayesianBanditClient
      .defaultConversionResource[F](rootUrl)
      .map(implicit c => new ConversionUpdater)
}
