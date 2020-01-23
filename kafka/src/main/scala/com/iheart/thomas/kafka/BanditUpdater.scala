package com.iheart.thomas
package kafka

import cats.effect._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.analysis.KPIName
import com.iheart.thomas.bandit.`package`.ArmName
import com.iheart.thomas.bandit.bayesian.ConversionBMABAlg
import com.iheart.thomas.stream.{ConversionBanditKPITracker, RestartableStream}
import com.iheart.thomas.stream.ConversionBanditKPITracker.ConversionEvent
import fs2.{Pipe, Stream}
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, Deserializer, consumerStream}
import cats.implicits._
import com.iheart.thomas.bandit.tracking.Event.BanditKPIUpdateStreamStarted
import com.iheart.thomas.bandit.tracking.EventLogger

import scala.concurrent.ExecutionContext

trait BanditUpdater[F[_]] {
  def consumer: Stream[F, Unit]
  def pauseResume(pause: Boolean): F[Unit]
  def isPaused: F[Boolean]
}

/**
  * For internal testing purpose
  */
private[kafka] trait WithConversionBMABAlg[F[_]] {
  def conversionBMABAlg: ConversionBMABAlg[F]
}

object BanditUpdater {

  def resource[
      F[_]: Timer: ContextShift: ConcurrentEffect: mongo.DAOs: EventLogger,
      Message
    ](kafkaConfig: KafkaConfig,
      toEvent: (FeatureName, KPIName) => F[
        Pipe[F, Message, (ArmName, ConversionEvent)]
      ]
    )(implicit ex: ExecutionContext,
      amazonClient: AmazonDynamoDBAsync,
      deserializer: Deserializer[F, Message]
    ): Resource[F, BanditUpdater[F]] = {
    implicit val mp = MessageProcessor(toEvent)
    resource[F](
      kafkaConfig
    )
  }

  def resource[
      F[_]: Timer: ContextShift: ConcurrentEffect: mongo.DAOs: MessageProcessor: EventLogger
    ](kafkaConfig: KafkaConfig
    )(implicit ex: ExecutionContext,
      amazonClient: AmazonDynamoDBAsync
    ): Resource[F, BanditUpdater[F]] =
    ConversionBMABAlgResource[F].evalMap(
      implicit alg => create[F](kafkaConfig)
    )

  def create[F[_]: Timer: ConcurrentEffect: ContextShift](
      kafkaConfig: KafkaConfig
    )(implicit mp: MessageProcessor[F],
      cbm: ConversionBMABAlg[F],
      log: EventLogger[F]
    ): F[BanditUpdater[F]] = {

    def runningStream = {

      import mp.deserializer

      val consumerSettings =
        ConsumerSettings[F, Unit, mp.RawMessage]
          .withEnableAutoCommit(true)
          .withAutoOffsetReset(AutoOffsetReset.Earliest)
          .withBootstrapServers(kafkaConfig.kafkaServers)
          .withGroupId("thomas-kpi-monitor")

      val toEvent = (fn: FeatureName, kn: KPIName) => mp.toConversionEvent(fn, kn)
      val updater = new ConversionBanditKPITracker[F]
      val updaterPipe = updater.updateAllConversions(kafkaConfig.chunkSize, toEvent)

      Stream.eval(log(BanditKPIUpdateStreamStarted)) ++
        consumerStream[F]
          .using(consumerSettings)
          .evalTap(_.subscribeTo(kafkaConfig.topic))
          .flatMap(_.stream)
          .map(r => r.record.value)
          .through(mp.preprocessor)
          .through(updaterPipe)
    }

    RestartableStream.restartable(runningStream).map {
      case (consumerStream, pauseSignal) =>
        new BanditUpdater[F] with WithConversionBMABAlg[F] {

          def conversionBMABAlg: ConversionBMABAlg[F] = cbm

          val consumer = consumerStream

          def pauseResume(pause: Boolean): F[Unit] =
            pauseSignal.set(pause) <* log.debug(s"pauseSignal set to $pause")

          def isPaused: F[Boolean] = pauseSignal.get

        }
    }
  }

  case class KafkaConfig(
      kafkaServers: String,
      topic: String,
      chunkSize: Int)

}
