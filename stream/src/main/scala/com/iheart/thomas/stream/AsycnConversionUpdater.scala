package com.iheart.thomas.stream

import cats.effect.{ConcurrentEffect, Resource}
import com.iheart.thomas.client.BayesianBanditClient
import fs2.Pipe
import AsyncConversionUpdater._
import cats.Functor
import com.iheart.thomas.FeatureName
import com.iheart.thomas.analysis.Conversions
import cats.implicits._
import com.iheart.thomas.bandit.`package`.ArmName

import scala.concurrent.ExecutionContext

class AsyncConversionUpdater[F[_]: Functor](
    chunkSize: Int,
    client: BayesianBanditClient[F, Conversions]) {

  def updateConversion(
      featureName: FeatureName
    ): Pipe[F, (ArmName, ConversionEvent), Unit] =
    AsyncConversionUpdater.toConversion(chunkSize) andThen {
      input =>
        input.evalMap { r =>
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

  def toConversion[F[_]](
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
      rootUrl: String,
      chunkSize: Int
    )(implicit ec: ExecutionContext
    ): Resource[F, AsyncConversionUpdater[F]] =
    BayesianBanditClient
      .defaultConversionResource[F](rootUrl)
      .map(c => new AsyncConversionUpdater(chunkSize, c))
}
