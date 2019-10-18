package com.iheart.thomas.kafka

import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime

import cats.effect.{ContextShift, IO, Resource, Sync, Timer}
import cats.effect.scalatest.AsyncIOSpec
import com.iheart.thomas.{FeatureName, dynamo, mongo}
import com.iheart.thomas.bandit.`package`.ArmName
import com.iheart.thomas.kafka.BanditUpdater.KafkaConfig
import com.iheart.thomas.stream.ConversionUpdater.ConversionEvent
import com.iheart.thomas.testkit.Resources
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.matchers.should.Matchers
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import fs2.Stream
import fs2.kafka._
import cats.implicits._
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.analysis.{Conversions, Probability, SampleSettings}
import com.iheart.thomas.bandit.{BanditSpec, BanditStateDAO}
import com.iheart.thomas.bandit.bayesian.{ArmState, ConversionBMABAlg}
import com.iheart.thomas.stream.ConversionUpdater
import com.stripe.rainier.sampler.RNG
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.scalatest.freespec.AnyFreeSpec

import concurrent.duration._
import scala.concurrent.ExecutionContext
class BanditUpdaterSuite extends AnyFreeSpec with Matchers with EmbeddedKafka {

  implicit val embeddedKafkaConfig = EmbeddedKafkaConfig(
    kafkaPort = 34563,
    customBrokerProperties = Map(
      "transaction.state.log.replication.factor" -> "1",
      "transaction.abort.timed.out.transaction.cleanup.interval.ms" -> 1.second.toMillis.toString
    )
  )
  implicit val executionContext: ExecutionContext = ExecutionContext.global
  implicit val ioContextShift: ContextShift[IO] =
    IO.contextShift(executionContext)
  implicit val ioTimer: Timer[IO] = IO.timer(executionContext)

  implicit val stringSerilizer = new StringSerializer
  val topic = "myTopic"

  implicit val deserializer
      : Deserializer[IO, (FeatureName, ArmName, ConversionEvent)] =
    Deserializer[IO, String].map { s =>
      s.split('|').toList match {
        case List(fn, an, ce) => (fn, an, ce == true.toString)
      }
    }

  val server = "localhost:" + embeddedKafkaConfig.kafkaPort

  val toEvent = (fn: FeatureName) =>
    IO.pure { (input: Stream[IO, (FeatureName, ArmName, ConversionEvent)]) =>
      input.collect {
        case (`fn`, am, ce) => (am, ce)
      }
    }

  val updaterR =
    Resources.mangoDAOs.flatMap { implicit daos =>
      Resources.localDynamoR.flatMap { implicit dynamoClient =>
        BanditUpdater.resource[IO, (FeatureName, ArmName, ConversionEvent)](
          kafkaConfig = KafkaConfig(
            server,
            topic,
            2
          ),
          toEvent
        )
      }
    }

  val spec = BanditSpec(
    feature = "feature1",
    arms = List("A", "B"),
    author = "Test Runner",
    start = OffsetDateTime.now,
    title = "for integration tests"
  )
  "Can update an bandit" in {
    withRunningKafka {
      createCustomTopic(topic)

      List("A|true", "A|false", "B|false", "B|false", "B|true", "B|true").foreach {
        m =>
          publishToKafka(topic, s"feature1|$m")
      }

      val spec = BanditSpec(
        feature = "feature1",
        arms = List("A", "B"),
        author = "Test Runner",
        start = OffsetDateTime.now,
        title = "for integration tests"
      )

      val resultState =
        updaterR
          .use { updaterPublic =>
            val updater = updaterPublic
              .asInstanceOf[BanditUpdater[IO] with WithConversionBMABAlg[
                IO
              ]]
            for {
              _ <- updater.conversionBMABAlg.init(spec)
              _ <- ioTimer.sleep(1.second) //wait for spec to start
              _ <- updater.consumeKafka
                .interruptAfter(10.seconds)
                .compile
                .toVector

              state <- updater.conversionBMABAlg.currentState("feature1")
            } yield state
          }
          .unsafeRunSync()

      resultState.state.arms.toSet shouldBe Set(
        ArmState("A", Conversions(1, 2), Probability(0d)),
        ArmState("B", Conversions(2, 4), Probability(0d))
      )
    }

  }

  final def consumerSettings[F[_]](
      config: EmbeddedKafkaConfig
    )(implicit F: Sync[F]
    ): ConsumerSettings[F, Unit, String] =
    ConsumerSettings[F, Unit, String]
      .withProperties(consumerProperties(config))
      .withRecordMetadata(_.timestamp.toString)

  final def consumerProperties(config: EmbeddedKafkaConfig): Map[String, String] =
    Map(
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> s"localhost:${config.kafkaPort}",
      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest",
      ConsumerConfig.GROUP_ID_CONFIG -> "group"
    )

}
