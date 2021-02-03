package com.iheart.thomas.testkit

import cats.effect.{ExitCode, IO, IOApp}
import com.iheart.thomas.GroupName
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

  def initMessage(
      gn1: GroupName,
      gn2: GroupName
    ) =
    s"""
      |{ 
      |   "page_shown": "front_page",
      |   
      |   "treatment-groups": {
      |      "feature1" : "$gn1",
      |      "feature2" : "$gn2"    
      |    }
      |     
      |}
      |""".stripMargin

  def clickMessage(
      gn1: GroupName,
      gn2: GroupName
    ) =
    s"""
      |{ 
      |   "click": "front_page_recommendation",
      |   "treatment-groups": {
      |      "feature1" : "$gn1",
      |      "feature2" : "$gn2"    
      |    }
      |        |
      |}
      |""".stripMargin

  val messages =
    Random.shuffle(
      List.fill(10)(initMessage("A", "B")) ++
        List.fill(13)(initMessage("B", "A")) ++
        List.fill(4)(clickMessage("A", "B")) ++
        List.fill(2)(clickMessage("B", "A"))
    )

}
