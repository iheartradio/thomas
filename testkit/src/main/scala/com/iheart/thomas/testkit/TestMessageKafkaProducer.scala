package com.iheart.thomas.testkit

import cats.effect.{ExitCode, IO, IOApp}
import com.iheart.thomas.kafka.KafkaConfig
import com.typesafe.config.ConfigFactory
import fs2.kafka.{KafkaProducer, ProducerRecord, ProducerRecords, ProducerSettings}
import fs2.Stream

import concurrent.duration._

object TestMessageKafkaProducer extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    Stream
      .eval(KafkaConfig.fromConfig[IO](ConfigFactory.load))
      .flatMap { cfg =>
        val producerSettings =
          ProducerSettings[IO, String, String]
            .withBootstrapServers("127.0.0.1:9092")
        Stream
          .fixedDelay(100.millis)
          .map { _ =>
            val key = "k"
            val value = "v"
            val record = ProducerRecord(cfg.topic, key, value)
            ProducerRecords.one(record)
          }
          .through(KafkaProducer.pipe(producerSettings))

      }
      .interruptAfter(5.seconds)
      .compile
      .drain
      .as(ExitCode.Success)

  }
}
