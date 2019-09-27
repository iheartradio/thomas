package com.iheart.thomas.stream

import cats.effect.{Async, ConcurrentEffect, Resource}
import com.iheart.thomas.client.BayesianBanditClient
import fs2.Pipe
import AsyncConversionUpdater._
import com.iheart.thomas.FeatureName
import com.iheart.thomas.analysis.Conversions
import cats.implicits._
import com.iheart.thomas.bandit.`package`.ArmName

import scala.concurrent.ExecutionContext

class AsyncConversionUpdater[F[_]: Async](
    chunkSize: Int,
    client: BayesianBanditClient[F, Conversions]
) {

  def updateCount(
      featureName: FeatureName
  ): Pipe[F, (ArmName, ConversionEvent), Unit] = { input =>
    input
      .chunkN(chunkSize, true)
      .evalMap { chunk =>
        val isConverted = identity[ConversionEvent] _
        val r = (chunk
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

        client
          .updateReward(featureName, r)
          .void
      }
  }
}

object AsyncConversionUpdater {
  type ConversionEvent = Boolean
  val Converted = true
  val Viewed = false

  def resource[F[_]: ConcurrentEffect](
      rootUrl: String,
      chunkSize: Int
  )(implicit ec: ExecutionContext): Resource[F, AsyncConversionUpdater[F]] =
    BayesianBanditClient
      .defaultConversionResource[F](rootUrl)
      .map(c => new AsyncConversionUpdater(chunkSize, c))
}
