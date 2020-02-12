package com.iheart.thomas
package kafka

import java.util.UUID

import cats.effect._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.analysis.{Conversions, KPIName}
import com.iheart.thomas.bandit.`package`.ArmName
import com.iheart.thomas.bandit.bayesian.{BayesianMAB, ConversionBMABAlg}
import com.iheart.thomas.stream.ConversionBanditKPITracker
import com.iheart.thomas.stream.ConversionBanditKPITracker.ConversionEvent
import fs2.{Pipe, Stream}
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, Deserializer, consumerStream}
import cats.implicits._
import com.iheart.thomas.bandit.tracking.{Event, EventLogger}
import fs2.concurrent.SignallingRef

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

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
  type ConversionBandits = Vector[BayesianMAB[Conversions]]

  def resource[
      F[_]: Timer: ContextShift: ConcurrentEffect: mongo.DAOs: EventLogger,
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
      F[_]: Timer: ContextShift: ConcurrentEffect: mongo.DAOs: MessageProcessor: EventLogger
    ](cfg: Config
    )(implicit ex: ExecutionContext,
      amazonClient: AmazonDynamoDBAsync
    ): Resource[F, BanditUpdater[F]] =
    ConversionBMABAlgResource[F].evalMap(
      implicit alg => create[F](cfg)
    )

  def create[F[_]: Timer: ConcurrentEffect: ContextShift](
      cfg: Config
    )(implicit mp: MessageProcessor[F],
      cbm: ConversionBMABAlg[F],
      log: EventLogger[F]
    ): F[BanditUpdater[F]] = {
    SignallingRef[F, Boolean](false).map { pauseSig =>
      val changingRunningBandit: Stream[F, ConversionBandits] =
        ((Stream.emit[F, Unit](()) ++ Stream
          .fixedDelay[F](cfg.checkRunningBanditsEvery))
          .evalMap(_ => cbm.runningBandits()))
          .scan(
            (
              Vector.empty[BayesianMAB[Conversions]],
              none[Vector[BayesianMAB[Conversions]]]
            )
          ) { (memo, current) =>
            val old = memo._1

            def banditIdentifier(b: BayesianMAB[_]) =
              (b.feature, b.kpiName, b.abtest.data.groups.map(_.name))

            (
              current,
              if (current.map(banditIdentifier) == old.map(banditIdentifier)) None
              else Some(current)
            )
          }
          .mapFilter(_._2)
          .evalTap(
            b =>
              log(
                Event.BanditKPIUpdate
                  .NewSetOfRunningBanditsDetected(b.map(_.feature))
              )
          )
          .pauseWhen(pauseSig)

      val mainStream =
        Stream
          .eval(
            ConcurrentEffect[F]
              .delay(
                UUID.randomUUID().toString
              )
              .flatTap(name => log.debug(s"starting consumer $name"))
          )
          .flatMap { r =>
            changingRunningBandit.switchMap { runningBandits =>
              import mp.deserializer

              val consumerSettings =
                ConsumerSettings[F, Unit, mp.RawMessage]
                  .withEnableAutoCommit(true)
                  .withAutoOffsetReset(AutoOffsetReset.Earliest)
                  .withBootstrapServers(cfg.kafka.kafkaServers)
                  .withGroupId("thomas-kpi-monitor")

              val toEvent =
                (fn: FeatureName, kn: KPIName) => mp.toConversionEvent(fn, kn)
              val updater = new ConversionBanditKPITracker[F](runningBandits, r)
              val updaterPipe =
                updater.updateAllConversions(cfg.kafka.chunkSize, toEvent)

              Stream.eval(log(Event.BanditKPIUpdate.UpdateStreamStarted)) ++
                consumerStream[F]
                  .using(consumerSettings)
                  .evalTap(_.subscribeTo(cfg.kafka.topic))
                  .flatMap(_.stream)
                  .map(r => r.record.value)
                  .through(mp.preprocessor)
                  .through(updaterPipe)
                  .pauseWhen(pauseSig)
            }
          }

      new BanditUpdater[F] with WithConversionBMABAlg[F] {

        def conversionBMABAlg: ConversionBMABAlg[F] = cbm

        def autoRestartAfterError: Stream[F, Unit] =
          cfg.restartOnErrorAfter.fold[Stream[F, Unit]](Stream.empty)(
            wait =>
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
      checkRunningBanditsEvery: FiniteDuration,
      restartOnErrorAfter: Option[FiniteDuration],
      kafka: KafkaConfig)

  case class KafkaConfig(
      kafkaServers: String,
      topic: String,
      chunkSize: Int)

}
