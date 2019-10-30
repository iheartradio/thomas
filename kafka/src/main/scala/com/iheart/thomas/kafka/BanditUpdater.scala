package com.iheart.thomas
package kafka

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.FeatureName
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

trait MessageProcessor[F[_], RawMessage, PreprocessedMessage] {
  def preprocessor: Pipe[F, RawMessage, PreprocessedMessage]
  def toConversionEvent(featureName: FeatureName): F[
    Pipe[F, PreprocessedMessage, (ArmName, ConversionEvent)]
  ]
}

object MessageProcessor {
  def apply[F[_], Message](
      toEvent: FeatureName => F[Pipe[F, Message, (ArmName, ConversionEvent)]]
    ): MessageProcessor[F, Message, Message] =
    new MessageProcessor[F, Message, Message] {
      def preprocessor: Pipe[F, Message, Message] = identity

      def toConversionEvent(
          featureName: FeatureName
        ): F[Pipe[F, Message, (ArmName, ConversionEvent)]] = toEvent(featureName)
    }
}

/**
  * For internal testing purpose
  */
private[kafka] trait WithConversionBMABAlg[F[_]] {
  def conversionBMABAlg: ConversionBMABAlg[F]
}

object BanditUpdater {

  def resource[F[_]: Timer: ContextShift: ConcurrentEffect: mongo.DAOs, Message](
      kafkaConfig: KafkaConfig,
      toEvent: FeatureName => F[
        Pipe[F, Message, (ArmName, ConversionEvent)]
      ]
    )(implicit ex: ExecutionContext,
      amazonClient: AmazonDynamoDBAsync,
      deserializer: Deserializer.Record[F, Message]
    ): Resource[F, BanditUpdater[F]] =
    resource[F, Message, Message](
      kafkaConfig,
      MessageProcessor(toEvent)
    )

  def resource[
      F[_]: Timer: ContextShift: ConcurrentEffect: mongo.DAOs,
      RawMessage,
      Preprocessed
    ](kafkaConfig: KafkaConfig,
      messageProcessor: MessageProcessor[F, RawMessage, Preprocessed]
    )(implicit ex: ExecutionContext,
      amazonClient: AmazonDynamoDBAsync,
      deserializer: Deserializer.Record[F, RawMessage]
    ): Resource[F, BanditUpdater[F]] = {
    for {
      conversionBMAB <- ConversionBMABAlgResource[F]
      logger <- Resource.liftF(Slf4jLogger.fromName[F]("thomas-kafka"))
    } yield {
      implicit val (c, l) = (conversionBMAB, logger)
      apply[F, RawMessage, Preprocessed](kafkaConfig, messageProcessor)
    }
  }

  def apply[
      F[_]: Timer: ConcurrentEffect: Logger: ContextShift: ConversionBMABAlg,
      RawMessage,
      Preprocessed
    ](kafkaConfig: KafkaConfig,
      messageProcessor: MessageProcessor[F, RawMessage, Preprocessed]
    )(implicit
      deserializer: Deserializer.Record[F, RawMessage]
    ): BanditUpdater[F] =
    apply[F, RawMessage, Preprocessed](
      kafkaConfig,
      messageProcessor.preprocessor,
      messageProcessor.toConversionEvent _
    )

  def apply[
      F[_]: Timer: ConcurrentEffect: Logger: ContextShift,
      RawMessage,
      Processed
    ](kafkaConfig: KafkaConfig,
      preprocessor: Pipe[F, RawMessage, Processed],
      toEvent: FeatureName => F[
        Pipe[F, Processed, (ArmName, ConversionEvent)]
      ]
    )(implicit
      cbm: ConversionBMABAlg[F],
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
