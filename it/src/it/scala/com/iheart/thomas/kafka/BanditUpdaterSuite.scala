package com.iheart.thomas
package kafka

import java.time.OffsetDateTime

import cats.effect.{ContextShift, IO, Sync, Timer}
import cats.implicits._
import com.iheart.thomas.analysis._
import com.iheart.thomas.bandit.BanditSpec
import com.iheart.thomas.bandit.`package`.ArmName
import com.iheart.thomas.bandit.bayesian.{ArmState, BanditSettings}
import com.iheart.thomas.bandit.tracking.EventLogger
import com.iheart.thomas.kafka.BanditUpdater.KafkaConfig
import com.iheart.thomas.stream.ConversionBanditUpdater
import com.iheart.thomas.stream.ConversionBanditUpdater.ConversionEvent
import com.iheart.thomas.testkit.Resources
import fs2.Stream
import fs2.kafka._
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class BanditUpdaterSuiteBase extends AnyFreeSpec with Matchers with EmbeddedKafka {

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

  val toEvent = (fn: FeatureName, _: KPIName) =>
    IO.pure { (input: Stream[IO, (FeatureName, ArmName, ConversionEvent)]) =>
      input.collect {
        case (`fn`, am, ce) => (am, ce)
      }
    }

  val kpi = BetaKPIDistribution(
    "test kpi",
    alphaPrior = 1000,
    betaPrior = 100000
  )

  implicit val logger = EventLogger.noop[IO]

  val updaterResource =
    Resources.mangoDAOs.flatMap { implicit daos =>
      Resources.localDynamoR
        .flatMap { implicit dynamoClient =>
          BanditUpdater.resource[IO, (FeatureName, ArmName, ConversionEvent)](
            cfg = BanditUpdater.Config(
              restartOnErrorAfter = None,
              allowedBanditsStaleness = 100.milliseconds,
              kafka = KafkaConfig(
                server,
                topic
              )
            ),
            toEvent
          )
        }
        .evalTap { _ =>
          implicit val kpiDAO = daos._3
          KPIDistributionApi.default.upsert(kpi)
        }
    }

  def spec(
      chunkSize: Int = 2,
      numOfChunksPerReallocate: Int = 100
    ) =
    IO.delay(
      BanditSpec(
        feature = "feature1",
        arms = List("A", "B"),
        author = "Test Runner",
        start = OffsetDateTime.now,
        title = "for integration tests",
        kpiName = kpi.name,
        historyRetention = None,
        specificSettings = BanditSettings.Conversion(
          eventChunkSize = chunkSize,
          reallocateEveryNChunk = numOfChunksPerReallocate
        )
      )
    )

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

class BanditUpdaterSuite extends BanditUpdaterSuiteBase {
  "reallocates bandit" in {
    withRunningKafka {
      createCustomTopic(topic)

      (1 to 200)
        .map(_ => List("A|true", "A|false", "B|false", "B|true", "B|true", "B|true"))
        .flatten
        .foreach { m =>
          publishToKafka(topic, s"feature1|$m")
        }

      val resultState =
        updaterResource
          .use { updaterPublic =>
            val updater = updaterPublic
              .asInstanceOf[BanditUpdater[IO] with WithConversionBMABAlg[
                IO
              ]]
            for {
              _ <- spec(300, 2) flatMap updater.conversionBMABAlg.init
              _ <- ioTimer.sleep(1.second) //wait for spec to start
              _ <- updater.consumer
                .interruptAfter(10.seconds) //10 seconds needed for all message processed
                .compile
                .drain

              state <- updater.conversionBMABAlg.currentState("feature1")
            } yield state
          }
          .unsafeRunSync()

      val sizes = resultState.abtest.data.groups.map(g => g.name -> g.size).toMap
      sizes("B").toDouble should be > 0.9d
      sizes("A").toDouble should be < 0.9d
    }
  }

  "Can update an bandit" in {
    withRunningKafka {
      createCustomTopic(topic)

      List("A|true", "A|false", "B|false", "B|false", "B|true", "B|true").foreach {
        m =>
          publishToKafka(topic, s"feature1|$m")
      }

      val resultState =
        updaterResource
          .use { updaterPublic =>
            val updater = updaterPublic
              .asInstanceOf[BanditUpdater[IO] with WithConversionBMABAlg[
                IO
              ]]
            for {
              _ <- spec() flatMap updater.conversionBMABAlg.init
              _ <- ioTimer.sleep(1.second) //wait for spec to start
              _ <- updater.consumer
                .interruptAfter(10.seconds) //10 seconds needed for all message processed
                .compile
                .drain

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

  "Can pause" in {
    withRunningKafka {
      createCustomTopic(topic)
      val count = new java.util.concurrent.atomic.AtomicLong(0)
      val publish = Stream.repeatEval(
        IO.delay {
          count.incrementAndGet()
          publishToKafka(topic, s"feature1|A|true")
        }
      )

      val (resultState) =
        updaterResource
          .use { updaterPublic =>
            val updater = updaterPublic
              .asInstanceOf[BanditUpdater[IO] with WithConversionBMABAlg[
                IO
              ]]
            for {
              _ <- spec() flatMap updater.conversionBMABAlg.init
              _ <- publish
                .concurrently(updater.consumer)
                .concurrently(Stream.eval(updater.pauseResume(true)))
                .interruptAfter(10.seconds)
                .compile
                .toVector

              state <- updater.conversionBMABAlg.currentState("feature1")
            } yield state
          }
          .unsafeRunSync()

      resultState.state.arms.head.rewardState.total should be < (10L)
    }
  }

  "Can keep up with the messages" in {
    withRunningKafka {
      createCustomTopic(topic)
      val count = new java.util.concurrent.atomic.AtomicLong(0)
      val publish = Stream.repeatEval(
        IO.delay {
          count.incrementAndGet()
          publishToKafka(topic, s"feature1|A|true")
        }
      )

      val resultState =
        updaterResource
          .use { updaterPublic =>
            val updater = updaterPublic
              .asInstanceOf[BanditUpdater[IO] with WithConversionBMABAlg[
                IO
              ]]
            for {
              _ <- spec() flatMap updater.conversionBMABAlg.init
              _ <- publish
                .concurrently(updater.consumer)
                .interruptAfter(10.seconds)
                .compile
                .toVector

              state <- updater.conversionBMABAlg.currentState("feature1")

            } yield state
          }
          .unsafeRunSync()

      resultState.state.arms.head.rewardState.total.toDouble should be > (count.get.toDouble * 0.6d)
    }
  }

  "Can restart after pause" in {
    withRunningKafka {
      createCustomTopic(topic)
      val count = new java.util.concurrent.atomic.AtomicLong(0)
      val publish = Stream.repeatEval(
        IO.delay {
          count.incrementAndGet()
          publishToKafka(topic, s"feature1|A|true")
        }
      )

      val (resultState) =
        updaterResource
          .use { updaterPublic =>
            val updater = updaterPublic
              .asInstanceOf[BanditUpdater[IO] with WithConversionBMABAlg[
                IO
              ]]
            for {
              _ <- spec() flatMap updater.conversionBMABAlg.init
              _ <- publish
                .concurrently(updater.consumer)
                .concurrently(
                  Stream.eval(updater.pauseResume(true)) ++
                    Stream.sleep(40.millis) ++
                    Stream.eval(updater.pauseResume(false))
                )
                .interruptAfter(10.seconds)
                .compile
                .toVector

              state <- updater.conversionBMABAlg.currentState("feature1")
            } yield state
          }
          .unsafeRunSync()

      resultState.state.arms.head.rewardState.total.toDouble should be > (count.get.toDouble * 0.6d)
    }

  }

  "Can update bandits dynamically i.e. pick up new bandits" in {
    withRunningKafka {
      createCustomTopic(topic)

      def spec2 =
        spec().map(
          _.copy(
            feature = "feature2",
            arms = List("A", "C")
          )
        )

      val publish = Stream.fixedDelay(50.millis) >> Stream.eval(
        IO.delay {
          List("A|true", "A|false", "B|false", "B|false", "B|true", "B|true")
            .foreach { m =>
              publishToKafka(topic, s"feature1|$m")
            }
          List("A|true", "A|false", "C|true", "C|false", "A|true", "C|true")
            .foreach { m =>
              publishToKafka(topic, s"feature2|$m")
            }
        }
      )

      val (resultState1, resultState2) =
        updaterResource
          .use { updaterPublic =>
            val updater = updaterPublic
              .asInstanceOf[BanditUpdater[IO] with WithConversionBMABAlg[
                IO
              ]]
            for {
              _ <- spec() flatMap updater.conversionBMABAlg.init
              _ <- publish
                .concurrently(updater.consumer)
                .concurrently(
                  Stream.sleep[IO](2.seconds) *> Stream
                    .eval(spec2.flatMap(updater.conversionBMABAlg.init))
                )
                .interruptAfter(12.seconds)
                .compile
                .toVector

              state1 <- updater.conversionBMABAlg.currentState("feature1")
              state2 <- updater.conversionBMABAlg.currentState("feature2")
            } yield (state1, state2)
          }
          .unsafeRunSync()
      resultState2.state.arms.head.rewardState.total should be > (40L)
    }

  }

  "Can update bandits in parallel" in {
    withRunningKafka {
      createCustomTopic(topic, partitions = 4) //force distribute to different consumers
      val count = new java.util.concurrent.atomic.AtomicLong(0)

      val totalPublish = 100L
      val publish = Stream.fixedDelay(5.millis) >> Stream
        .eval(IO.delay(count.getAndIncrement()).map { c =>
          if (c < totalPublish)
            publishToKafka(topic, s"feature1|A|true")
          else ()
        })

      val result = updaterResource
        .use { updaterPublic =>
          val updater = updaterPublic
            .asInstanceOf[BanditUpdater[IO] with WithConversionBMABAlg[
              IO
            ]]
          for {
            _ <- spec(1) flatMap updater.conversionBMABAlg.init
            _ <- publish
              .concurrently(updater.consumer)
              .concurrently(updater.consumer)
              .concurrently(updater.consumer)
              .interruptAfter(10.seconds)
              .compile
              .toVector

            state <- updater.conversionBMABAlg.currentState("feature1")
          } yield state
        }
        .unsafeRunSync()

      result.state.arms
        .find(_.name == "A")
        .get
        .rewardState
        .total shouldBe totalPublish
    }
  }

}
