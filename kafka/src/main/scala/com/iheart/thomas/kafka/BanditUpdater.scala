package com.iheart.thomas
package kafka

import java.time.OffsetDateTime

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import abtest.AbtestAlg
import analysis.SampleSettings
import bandit.BanditStateDAO
import bandit.`package`.ArmName
import bandit.bayesian.ConversionBMABAlg
import stream.ConversionUpdater
import stream.ConversionUpdater.ConversionEvent
import com.stripe.rainier.sampler.RNG
import fs2.Pipe
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, Deserializer, consumerStream}
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

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

    import mongo.idSelector

    val updaterR: Resource[F, (ConversionUpdater[F], ConversionBMABAlg[F])] =
      for {
        conversionBMAB <- {
          implicit val stateDAO =
            BanditStateDAO.bayesianfromLihua(
              dynamo.DAOs.lihuaStateDAO[F](amazonClient)
            )
          implicit val (abtestDAO, featureDAO, kpiDAO) = mongoDAOs
          lazy val refreshPeriod = 0.seconds

          AbtestAlg.defaultResource[F](refreshPeriod).map { implicit abtestAlg =>
            implicit val ss = SampleSettings.default
            implicit val rng = RNG.default
            implicit val nowF = F.delay(OffsetDateTime.now)
            ConversionBMABAlg.default[F]
          }
        }
        logger <- Resource.liftF(Slf4jLogger.fromName[F]("thomas-kafka"))
      } yield {
        implicit val (c, l) = (conversionBMAB, logger)
        (new ConversionUpdater[F], conversionBMAB)
      }

    updaterR.map {
      case (updater, cba) =>
        new BanditUpdater[F] with WithConversionBMABAlg[F] {
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

          override def conversionBMABAlg: ConversionBMABAlg[F] = cba
        }
    }

  }

  case class KafkaConfig(
      kafkaServers: String,
      topic: String,
      chunkSize: Int)

}
