package com.iheart.thomas.stream

import cats.effect.{ConcurrentEffect, Resource}
import com.iheart.thomas.client.BayesianBanditClient
import fs2.Pipe
import ConversionUpdater._
import cats.Functor
import com.iheart.thomas.FeatureName
import com.iheart.thomas.analysis.Conversions
import cats.implicits._
import com.iheart.thomas.bandit.`package`.ArmName
import com.iheart.thomas.bandit.bayesian.BayesianMABAlg

import scala.concurrent.ExecutionContext

//trait ConversionUpdater[F[_], I] {
//  def updateConversions()
//}

class ConversionUpdater[F[_]: Functor](
    client: BayesianMABAlg[F, Conversions]) {

  def updateConversion(
      chunkSize: Int,
      featureName: FeatureName
    ): Pipe[F, (ArmName, ConversionEvent), Unit] =
    ConversionUpdater.toConversion(chunkSize) andThen { input =>
      input.evalMap { r =>
        client
          .updateRewardState(featureName, r)
          .void
      }
    }
}

object ConversionUpdater {
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
      rootUrl: String
    )(implicit ec: ExecutionContext
    ): Resource[F, ConversionUpdater[F]] =
    BayesianBanditClient
      .defaultConversionResource[F](rootUrl)
      .map(c => new ConversionUpdater(c))
}
