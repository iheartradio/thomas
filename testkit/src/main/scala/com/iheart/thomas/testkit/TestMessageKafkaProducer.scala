package com.iheart.thomas.testkit

import cats.effect.{ExitCode, IO, IOApp}
import com.iheart.thomas.{FeatureName, GroupName}
import com.iheart.thomas.kafka.KafkaConfig
import com.typesafe.config.ConfigFactory
import fs2.kafka.{KafkaProducer, ProducerRecord, ProducerRecords, ProducerSettings}
import fs2.Stream

import java.time.Instant
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
            .fixedDelay[IO](100.millis)
            .flatMap { _ =>
              Stream.fromIterator[IO](messages.iterator, 1).map { value =>
                val record = ProducerRecord(cfg.topic, "k", value)
                ProducerRecords.one(record)
              }
            }
            .through(KafkaProducer.pipe(producerSettings))

        }
        .interruptAfter(duration)).compile.drain
      .as(ExitCode.Success)

  }

  def message(
      eventString: String,
      groups: Seq[(FeatureName, GroupName)]
    ) = {
    val groupValues = groups
      .map { case (fn, gn) =>
        s""" "$fn" : "$gn" """
      }
      .mkString(""",
          | """.stripMargin)

    s"""
      |{ 
      |   $eventString,
      |   "treatment-groups": {
      |      $groupValues,
      |      timeStamp: ${Instant.now.toEpochMilli}
      |    }
      |}
      |""".stripMargin
  }

  val initEvent =
    """ "page_shown": "front_page" """

  val clickEvent =
    """ "click": "front_page_recommendation" """

  val groups = List("A", "B", "C", "D", "E", "F", "G", "H", "J")
  val features = List("A_Feature", "Another_Feature", "Third_Feature")

  def randomFG(n: Int) =
    List.fill(n)((Random.shuffle(features).head, Random.shuffle(groups).head))

  def randomMessage(
      n: Int,
      event: String
    ): List[String] =
    List.fill(n)(message(event, randomFG(Random.nextInt(5) + 1)))

  val messages: List[String] =
    randomMessage(100, initEvent) ++ randomMessage(40, clickEvent)
}
