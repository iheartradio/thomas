package com.iheart.thomas.kafka

import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import cats.implicits._
import com.iheart.thomas.stream.MessageSubscriber
import com.typesafe.config.Config
import fs2.Stream
import fs2.kafka.{AutoOffsetReset, ConsumerSettings}
import io.chrisdavenport.log4cats.Logger
import org.typelevel.jawn.ast
import org.typelevel.jawn.ast.JValue

object JsonMessageSubscriber {

  implicit def apply[F[_]: ConcurrentEffect: Timer: ContextShift](
      implicit config: Config,
      log: Logger[F]
    ): MessageSubscriber[F, JValue] =
    new MessageSubscriber[F, JValue] {
      override def subscribe: Stream[F, JValue] =
        Stream.eval(KafkaConfig.fromConfig[F](config)).flatMap { cfg =>
          val consumerSettings =
            ConsumerSettings[F, Unit, String]
              .withEnableAutoCommit(true)
              .withAutoOffsetReset(AutoOffsetReset.Earliest)
              .withBootstrapServers(cfg.kafkaServers)
              .withGroupId(cfg.groupId)

          fs2.kafka.KafkaConsumer
            .stream[F]
            .using(consumerSettings)
            .evalTap(_.subscribeTo(cfg.topic))
            .map {
              _.stream.evalMap { r =>
                ast.JParser
                  .parseFromString(r.record.value)
                  .fold(
                    e =>
                      log
                        .error(
                          s"kafka message json parse error. $e \n json: ${r.record.value}"
                        )
                        .as(none[JValue]),
                    j => Option(j).pure[F]
                  )
              }.flattenOption

            }
            .flatten

        }
    }

}
