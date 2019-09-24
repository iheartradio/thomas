package com.iheart.thomas.stream

import cats.effect.Async
import com.iheart.thomas.client.BayesianBanditClient
import fs2.Pipe
import AsyncConversionUpdater._
import com.iheart.thomas.FeatureName
import com.iheart.thomas.analysis.Conversions
import cats.implicits._

abstract class AsyncConversionUpdater[F[_]: Async](
    chunkSize: Int,
    client: BayesianBanditClient[F, Conversions]
) {

  def updateCount(featureName: FeatureName): Pipe[F, ConversionEvent, Unit] = { input =>
    input.chunkN(chunkSize, true).evalMap { chunk =>
      val isConverted = identity[ConversionEvent] _
      val convertedCount = chunk.filter(isConverted).size
      client
        .updateReward(
          featureName,
          Conversions(converted = convertedCount.toLong, total = chunk.size.toLong))
        .void
    }
  }
}

object AsyncConversionUpdater {
  type ConversionEvent = Boolean
  val Converted = true
  val Viewed = false

}
