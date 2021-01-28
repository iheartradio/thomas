package com.iheart.thomas.testkit

import cats.effect.{ExitCode, IO, IOApp}
import com.iheart.thomas.kafka.KafkaConfig
import com.typesafe.config.ConfigFactory
import fs2.kafka.{KafkaProducer, ProducerRecord, ProducerRecords, ProducerSettings}
import fs2.Stream

import concurrent.duration._
import scala.util.{Random, Try}

object TestMessageKafkaProducer extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    val duration =
      args.headOption.flatMap(h => Try(h.toInt).toOption).getOrElse(3).seconds
    (Stream.eval {
      IO.delay {
        println("=====================================")
        println(s"Running kafka producer for $duration")
        println("=====================================")
      }
    } ++
      Stream
        .eval(KafkaConfig.fromConfig[IO](ConfigFactory.load))
        .flatMap { cfg =>
          val producerSettings =
            ProducerSettings[IO, String, String]
              .withBootstrapServers("127.0.0.1:9092")
          Stream
            .fixedDelay(100.millis)
            .flatMap { _ =>
              Stream.fromIterator[IO](messages.iterator).map { value =>
                val record = ProducerRecord(cfg.topic, "k", value)
                ProducerRecords.one(record)
              }
            }
            .through(KafkaProducer.pipe(producerSettings))

        }
        .interruptAfter(duration)).compile.drain
      .as(ExitCode.Success)

  }

  val initMessage =
    """
      |{ 
      |   "page_shown": "front_page"
      |}
      |""".stripMargin

  val clickMessage =
    """
      |{ 
      |   "click": "front_page_recommendation"
      |}
      |""".stripMargin

  val messages =
    Random.shuffle(List.fill(10)(initMessage) ++ List.fill(4)(clickMessage))

}
