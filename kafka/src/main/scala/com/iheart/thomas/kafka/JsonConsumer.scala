package com.iheart.thomas.kafka

import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import cats.implicits._
import com.iheart.thomas.stream.{JobAlg, JobRunnerConfig}
import com.typesafe.config.Config
import fs2.kafka.{AutoOffsetReset, ConsumerSettings}
import org.typelevel.jawn.ast.JValue
import io.chrisdavenport.log4cats.Logger
import fs2.Stream
import org.typelevel.jawn.ast

object JsonConsumer {
  def create[F[_]: ConcurrentEffect: Timer: ContextShift](
      cfg: KafkaConfig
    )(implicit log: Logger[F]
    ): Stream[F, Stream[F, JValue]] = {
    val consumerSettings =
      ConsumerSettings[F, Unit, String]
        .withEnableAutoCommit(true)
        .withAutoOffsetReset(AutoOffsetReset.Earliest)
        .withBootstrapServers(cfg.kafkaServers)
        .withGroupId(cfg.groupId)

    fs2.kafka
      .consumerStream[F]
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
  }

  def jobStream[F[_]: ConcurrentEffect: Timer: ContextShift: Logger](
      cfg: Config
    )(implicit jobAlg: JobAlg[F, JValue]
    ): Stream[F, Unit] = {
    for {
      kf <- Stream.eval(KafkaConfig.fromConfig[F](cfg))
      jf <- Stream.eval(JobRunnerConfig.fromConfig[F](cfg))
      s <- create[F](kf).flatten.through(jobAlg.runningPipe(jf))
    } yield s

  }

}
