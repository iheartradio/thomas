package com.iheart.thomas
package kafka

import java.util.UUID
import cats.NonEmptyParallel
import cats.effect._
import cats.implicits._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.analysis.KPIName
import com.iheart.thomas.analysis.ConversionEvent
import com.iheart.thomas.bandit.bayesian.ConversionBMABAlg
import com.iheart.thomas.bandit.tracking.{Event, EventLogger}
import com.iheart.thomas.stream.ConversionBanditUpdater
import fs2.concurrent.SignallingRef
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, Deserializer, KafkaConsumer}
import fs2.{Pipe, Stream}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

//todo to be retired
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
      F[_]: Timer: ContextShift: ConcurrentEffect: mongo.DAOs: EventLogger: NonEmptyParallel,
      Message
    ](cfg: Config,
      toEvent: (FeatureName, KPIName) => F[
        Pipe[F, Message, (ArmName, ConversionEvent)]
      ]
    )(implicit ex: ExecutionContext,
      amazonClient: AmazonDynamoDBAsync,
      deserializer: Deserializer[F, Message]
    ): Resource[F, BanditUpdater[F]] = {
    implicit val mp = MessageProcessor(toEvent)
    resource[F](
      cfg
    )
  }

  def resource[
      F[_]: Timer: ContextShift: ConcurrentEffect: mongo.DAOs: MessageProcessor: EventLogger: NonEmptyParallel
    ](cfg: Config
    )(implicit ex: ExecutionContext,
      amazonClient: AmazonDynamoDBAsync
    ): Resource[F, BanditUpdater[F]] =
    ConversionBMABAlgResource[F].evalMap(implicit alg => create[F](cfg))

  def create[F[_]: Timer: ConcurrentEffect: ContextShift](
      cfg: Config
    )(implicit mp: MessageProcessor[F],
      cbm: ConversionBMABAlg[F],
      log: EventLogger[F]
    ): F[BanditUpdater[F]] = {

    SignallingRef[F, Boolean](false).map { pauseSig =>
      val mainStream =
        Stream
          .eval(
            ConcurrentEffect[F]
              .delay(
                UUID.randomUUID().toString
              )
              .flatTap(name => log.debug(s"Starting Consumer $name"))
          )
          .flatMap { name =>
            ConversionBanditUpdater
              .updatePipes(
                name,
                cfg.allowedBanditsStaleness,
                (fn: FeatureName, kn: KPIName) => mp.toConversionEvent(fn, kn)
              )
              .switchMap { updatePipes =>
                import mp.deserializer

                val consumerSettings =
                  ConsumerSettings[F, Unit, mp.RawMessage]
                    .withEnableAutoCommit(true)
                    .withAutoOffsetReset(AutoOffsetReset.Earliest)
                    .withBootstrapServers(cfg.kafka.kafkaServers)
                    .withGroupId(cfg.kafka.groupId)

                Stream.eval(log(Event.BanditKPIUpdate.UpdateStreamStarted)) ++
                  KafkaConsumer
                    .stream[F]
                    .using(consumerSettings)
                    .evalTap(_.subscribeTo(cfg.kafka.topic))
                    .flatMap(_.stream.pauseWhen(pauseSig))
                    .map(r => r.record.value)
                    .through(mp.preprocessor)
                    .through(updatePipes)

              }
          }

      new BanditUpdater[F] with WithConversionBMABAlg[F] {

        def conversionBMABAlg: ConversionBMABAlg[F] = cbm

        def autoRestartAfterError: Stream[F, Unit] =
          cfg.restartOnErrorAfter.fold[Stream[F, Unit]](Stream.empty)(wait =>
            Stream.sleep[F](wait) ++
              consumer
          )

        val consumer: Stream[F, Unit] = mainStream.handleErrorWith { e =>
          Stream.eval(log(Event.BanditKPIUpdate.Error(e))) ++ autoRestartAfterError
        }

        def pauseResume(pause: Boolean): F[Unit] =
          pauseSig.set(pause) <* log.debug(s"pauseSignal set to $pause")

        def isPaused: F[Boolean] = pauseSig.get

      }
    }
  }

  case class Config(
      restartOnErrorAfter: Option[FiniteDuration],
      allowedBanditsStaleness: FiniteDuration,
      kafka: KafkaConfig)

}
