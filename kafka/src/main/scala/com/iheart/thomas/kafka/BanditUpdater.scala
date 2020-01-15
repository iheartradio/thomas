package com.iheart.thomas
package kafka

import cats.effect._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.analysis.KPIName
import com.iheart.thomas.bandit.`package`.ArmName
import com.iheart.thomas.bandit.bayesian.ConversionBMABAlg
import com.iheart.thomas.stream.ConversionBanditKPITracker
import com.iheart.thomas.stream.ConversionBanditKPITracker.ConversionEvent
import fs2.concurrent.SignallingRef
import fs2.{Pipe, Stream}
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, Deserializer, consumerStream}
import cats.implicits._
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

  def create[
      F[_]: Timer: ContextShift: ConcurrentEffect: MessageProcessor: ConversionBMABAlg: EventLogger
    ](kafkaConfig: KafkaConfig
    ): F[BanditUpdater[F]] =
    SignallingRef[F, Boolean](false).map { pauseSignal =>
      apply[F](
        kafkaConfig,
        pauseSignal
      )
    }

  def apply[F[_]: Timer: ConcurrentEffect: EventLogger: ContextShift](
      kafkaConfig: KafkaConfig,
      pauseSignal: SignallingRef[F, Boolean]
    )(implicit mp: MessageProcessor[F],
      cbm: ConversionBMABAlg[F]
    ): BanditUpdater[F] = new BanditUpdater[F] with WithConversionBMABAlg[F] {
    import mp.deserializer
    val updater = new ConversionBanditKPITracker[F]
    val consumer: Stream[F, Unit] = {
      val consumerSettings =
        ConsumerSettings[F, Unit, mp.RawMessage]
          .withEnableAutoCommit(true)
          .withAutoOffsetReset(AutoOffsetReset.Earliest)
          .withBootstrapServers(kafkaConfig.kafkaServers)
          .withGroupId("thomas-kpi-monitor")
      val toEvent = (fn: FeatureName, kn: KPIName) => mp.toConversionEvent(fn, kn)

      val updaterPipe = updater.updateAllConversions(kafkaConfig.chunkSize, toEvent)

      consumerStream[F]
        .using(consumerSettings)
        .evalTap(_.subscribeTo(kafkaConfig.topic))
        .flatMap(_.stream)
        .map(r => r.record.value)
        .through(mp.preprocessor)
        .through(updaterPipe)
        .pauseWhen(pauseSignal)

    }

    def conversionBMABAlg: ConversionBMABAlg[F] = cbm

    def pauseResume(pause: ConversionEvent): F[Unit] =
      pauseSignal.set(pause)

    def isPaused: F[ConversionEvent] = pauseSignal.get
  }

  case class KafkaConfig(
      kafkaServers: String,
      topic: String,
      chunkSize: Int)

}
