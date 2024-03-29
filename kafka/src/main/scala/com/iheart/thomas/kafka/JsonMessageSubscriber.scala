package com.iheart.thomas.kafka

import cats.effect.Async
import cats.implicits._
import com.iheart.thomas.stream.MessageSubscriber
import com.typesafe.config.Config
import fs2.Stream
import fs2.kafka.{AutoOffsetReset, ConsumerSettings}
import org.typelevel.log4cats.Logger
import org.typelevel.jawn.ast
import org.typelevel.jawn.ast.JValue

object JsonMessageSubscriber {

  implicit def apply[F[_]: Async](
      implicit config: Config,
      log: Logger[F]
    ): MessageSubscriber[F, JValue] =
    new MessageSubscriber[F, JValue] {
      override def subscribe: Stream[F, JValue] =
        Stream.eval(KafkaConfig.fromConfig[F](config)).flatMap { cfg =>
          val consumerSettings =
            ConsumerSettings[F, Unit, String]
              .withEnableAutoCommit(true)
              .withAutoOffsetReset(AutoOffsetReset.Latest)
              .withBootstrapServers(cfg.kafkaServers)
              .withGroupId(cfg.groupId)

          fs2.kafka.KafkaConsumer
            .stream(consumerSettings)
            .evalTap(_.subscribeTo(cfg.topic))
            .flatMap {
              _.stream
                .parEvalMap(cfg.parseParallelization) { r =>
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
                }
                .flattenOption

            }

        }
    }

}
