package com.iheart.thomas
package kafka

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.bandit.`package`.ArmName
import com.iheart.thomas.bandit.bayesian.ConversionBMABAlg
import com.iheart.thomas.stream.ConversionUpdater
import com.iheart.thomas.stream.ConversionUpdater.ConversionEvent
import fs2.{Pipe, Stream}
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, Deserializer, consumerStream}
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

import scala.concurrent.ExecutionContext

trait BanditUpdater[F[_]] {
  def consumeKafka: Stream[F, Unit]
}

/**
  * For internal testing purpose
  */
private[kafka] trait WithConversionBMABAlg[F[_]] {
  def conversionBMABAlg: ConversionBMABAlg[F]
}

object BanditUpdater {

  def resource[F[_]: Timer: ContextShift, Message](
      kafkaConfig: KafkaConfig,
      toEvent: FeatureName => F[
        Pipe[F, Message, (ArmName, ConversionEvent)]
      ]
    )(implicit ex: ExecutionContext,
      F: ConcurrentEffect[F],
      mongoDAOs: mongo.DAOs[F],
      amazonClient: AmazonDynamoDBAsync,
      deserializer: Deserializer.Record[F, Message]
    ): Resource[F, BanditUpdater[F]] =
    resource[F, Message, Message](
      kafkaConfig,
      identity,
      toEvent: FeatureName => F[
        Pipe[F, Message, (ArmName, ConversionEvent)]
      ]
    )

  def resource[F[_]: Timer: ContextShift, RawMessage, Processed](
      kafkaConfig: KafkaConfig,
      preprocessor: Pipe[F, RawMessage, Processed],
      toEvent: FeatureName => F[
        Pipe[F, Processed, (ArmName, ConversionEvent)]
      ]
    )(implicit ex: ExecutionContext,
      F: ConcurrentEffect[F],
      mongoDAOs: mongo.DAOs[F],
      amazonClient: AmazonDynamoDBAsync,
      deserializer: Deserializer.Record[F, RawMessage]
    ): Resource[F, BanditUpdater[F]] = {
    for {
      conversionBMAB <- ConversionBMABAlgResource[F]
      logger <- Resource.liftF(Slf4jLogger.fromName[F]("thomas-kafka"))
    } yield {
      implicit val (c, l) = (conversionBMAB, logger)
      apply[F, RawMessage, Processed](kafkaConfig, preprocessor, toEvent)
    }
  }

  def apply[F[_]: Timer: ContextShift, RawMessage, Processed](
      kafkaConfig: KafkaConfig,
      preprocessor: Pipe[F, RawMessage, Processed],
      toEvent: FeatureName => F[
        Pipe[F, Processed, (ArmName, ConversionEvent)]
      ]
    )(implicit ex: ExecutionContext,
      F: ConcurrentEffect[F],
      cbm: ConversionBMABAlg[F],
      logger: Logger[F],
      deserializer: Deserializer.Record[F, RawMessage]
    ): BanditUpdater[F] = new BanditUpdater[F] with WithConversionBMABAlg[F] {
    val updater = new ConversionUpdater[F]
    def consumeKafka: Stream[F, Unit] = {
      val consumerSettings =
        ConsumerSettings[F, Unit, RawMessage]
          .withEnableAutoCommit(true)
          .withAutoOffsetReset(AutoOffsetReset.Earliest)
          .withBootstrapServers(kafkaConfig.kafkaServers)
          .withGroupId("thomas-kpi-monitor")

      Stream
        .eval(updater.updateAllConversions(kafkaConfig.chunkSize, toEvent))
        .flatMap { updatePipe =>
          consumerStream[F]
            .using(consumerSettings)
            .evalTap(_.subscribeTo(kafkaConfig.topic))
            .flatMap(_.stream)
            .map(r => r.record.value)
            .through(preprocessor)
            .through(updatePipe)
        }
    }

    def conversionBMABAlg: ConversionBMABAlg[F] = cbm
  }

  case class KafkaConfig(
      kafkaServers: String,
      topic: String,
      chunkSize: Int)

}
