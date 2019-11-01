package com.iheart.thomas
package kafka

import cats.effect._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.FeatureName
import com.iheart.thomas.analysis.KPIName
import com.iheart.thomas.bandit.`package`.ArmName
import com.iheart.thomas.bandit.bayesian.ConversionBMABAlg
import com.iheart.thomas.stream.ConversionUpdater
import com.iheart.thomas.stream.ConversionUpdater.ConversionEvent
import fs2.concurrent.SignallingRef
import fs2.{Pipe, Stream}
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, Deserializer, consumerStream}
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import cats.implicits._

import scala.concurrent.ExecutionContext

trait BanditUpdater[F[_]] {
  def consumer: Stream[F, Unit]
  def pauseResume(pause: Boolean): F[Unit]
  def isPaused: F[Boolean]
}

trait MessageProcessor[F[_]] {
  type RawMessage
  type PreprocessedMessage

  implicit def deserializer: Deserializer.Record[F, RawMessage]
  def preprocessor: Pipe[F, RawMessage, PreprocessedMessage]
  def toConversionEvent(
      featureName: FeatureName,
      KPIName: KPIName
    ): F[
    Pipe[F, PreprocessedMessage, (ArmName, ConversionEvent)]
  ]
}

object MessageProcessor {
  def apply[F[_], Message](
      toEvent: (FeatureName,
          KPIName) => F[Pipe[F, Message, (ArmName, ConversionEvent)]]
    )(implicit ev: Deserializer.Record[F, Message]
    ) =
    new MessageProcessor[F] {

      type RawMessage = Message
      type PreprocessedMessage = Message
      implicit def deserializer: Deserializer.Record[F, Message] = ev
      def preprocessor: Pipe[F, Message, Message] = identity

      def toConversionEvent(
          featureName: FeatureName,
          kpiName: KPIName
        ): F[Pipe[F, Message, (ArmName, ConversionEvent)]] =
        toEvent(featureName, kpiName)
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
      F[_]: Timer: ContextShift: ConcurrentEffect: mongo.DAOs: MessageProcessor
    ](kafkaConfig: KafkaConfig
    )(implicit ex: ExecutionContext,
      amazonClient: AmazonDynamoDBAsync
    ): Resource[F, BanditUpdater[F]] =
    ConversionBMABAlgResource[F].evalMap(
      implicit alg => create[F](kafkaConfig)
    )

  def create[
      F[_]: Timer: ContextShift: ConcurrentEffect: MessageProcessor: ConversionBMABAlg
    ](kafkaConfig: KafkaConfig
    ): F[BanditUpdater[F]] =
    Slf4jLogger.fromName[F]("thomas-kafka").flatMap { implicit logger =>
      SignallingRef[F, Boolean](false).map { pauseSignal =>
        apply[F](
          kafkaConfig,
          pauseSignal
        )
      }
    }

  def apply[F[_]: Timer: ConcurrentEffect: Logger: ContextShift](
      kafkaConfig: KafkaConfig,
      pauseSignal: SignallingRef[F, Boolean]
    )(implicit mp: MessageProcessor[F],
      cbm: ConversionBMABAlg[F]
    ): BanditUpdater[F] = new BanditUpdater[F] with WithConversionBMABAlg[F] {
    import mp.deserializer
    val updater = new ConversionUpdater[F]
    val consumer: Stream[F, Unit] = {
      val consumerSettings =
        ConsumerSettings[F, Unit, mp.RawMessage]
          .withEnableAutoCommit(true)
          .withAutoOffsetReset(AutoOffsetReset.Earliest)
          .withBootstrapServers(kafkaConfig.kafkaServers)
          .withGroupId("thomas-kpi-monitor")
      val toEvent = (fn: FeatureName, kn: KPIName) => mp.toConversionEvent(fn, kn)

      Stream
        .eval(
          updater.updateAllConversions(kafkaConfig.chunkSize, toEvent)
        )
        .flatMap { updatePipe =>
          consumerStream[F]
            .using(consumerSettings)
            .evalTap(_.subscribeTo(kafkaConfig.topic))
            .flatMap(_.stream)
            .map(r => r.record.value)
            .through(mp.preprocessor)
            .through(updatePipe)
            .pauseWhen(pauseSignal)
        }
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
