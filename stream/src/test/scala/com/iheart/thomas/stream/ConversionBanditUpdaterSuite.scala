package com.iheart.thomas
package stream

import java.time.OffsetDateTime
import fs2.Stream
import cats.effect.IO
import org.scalatest.matchers.should.Matchers
import cats.effect.testing.scalatest.AsyncIOSpec
import com.iheart.thomas.FeatureName
import com.iheart.thomas.abtest.model.{Abtest, Group, TestName}
import com.iheart.thomas.analysis._
import com.iheart.thomas.bandit.BanditSpec
import com.iheart.thomas.bandit.bayesian.{
  BanditSettings,
  BanditState,
  BayesianMAB,
  BayesianMABAlg,
  ConversionBandit
}
import com.iheart.thomas.tracking.EventLogger
import lihua.{Entity, EntityId}

import concurrent.duration._

class ConversionBanditUpdaterSuite extends AsyncIOSpec with Matchers {
  "toConversion" - {
    "count conversions per arm" in {
      val input = Stream.fromIterator[IO](
        List(
          "A" -> Viewed,
          "B" -> Viewed,
          "B" -> Converted,
          "A" -> Converted,
          "B" -> Viewed,
          "B" -> Viewed
        ).iterator
      )
      ConversionBanditUpdater
        .toConversion[IO](10)(input)
        .compile
        .toList
        .asserting(
          _ shouldBe List(
            Map(
              "A" -> Conversions(1, 1),
              "B" -> Conversions(1, 3)
            )
          )
        )

    }

  }

  "running bandits" - {
    implicit val logger = EventLogger.noop[IO]

    def mockBandit(
        feature: FeatureName,
        eventChunkSize: Int = 1,
        groups: List[String] = List("A", "B")
      ): ConversionBandit =
      BayesianMAB[Conversions, BanditSettings.Conversion](
        Entity(
          _id = EntityId(feature),
          data = Abtest(
            name = "b",
            feature = feature,
            author = "a",
            start = null,
            end = null,
            groups = groups.map(Group(_, 0.1d, None)),
            ranges = null
          )
        ),
        BanditSettings(
          feature = feature,
          title = "blah",
          author = "author",
          kpiName = KPIName("blah"),
          minimumSizeChange = 0.1d,
          distSpecificSettings = BanditSettings
            .Conversion(eventChunkSize = eventChunkSize, updatePolicyEveryNChunk = 1)
        ),
        null
      )

    def mockCbm(lists: Vector[ConversionBandit]*) =
      new BayesianMABAlg[IO, Conversions, BanditSettings.Conversion] {
        val i = new java.util.concurrent.atomic.AtomicInteger(0)

        def updateRewardState(
            featureName: FeatureName,
            rewardState: Map[ArmName, Conversions]
          ): IO[BanditState[Conversions]] = ???

        def init(banditSpec: BanditSpec[BanditSettings.Conversion]): IO[Bandit] = ???

        def currentState(featureName: FeatureName): IO[Bandit] = ???

        def getAll: IO[Vector[Bandit]] = ???

        def updatePolicy(featureName: FeatureName): IO[Bandit] = ???

        def delete(featureName: FeatureName): IO[Unit] = ???

        def update(
            banditSettings: BanditSettings[BanditSettings.Conversion]
          ): IO[BanditSettings[BanditSettings.Conversion]] = ???

        def runningBandits(time: Option[OffsetDateTime]): IO[Vector[Bandit]] =
          IO {
            lists(Math.min(i.getAndIncrement(), lists.length - 1))
          }
      }

    "doesn't change when there is not change" in {
      implicit val cbm = mockCbm(Vector(mockBandit("f1")), Vector(mockBandit("f1")))
      ConversionBanditUpdater
        .runningBandits[IO](25.milliseconds)
        .interruptAfter(75.milliseconds)
        .compile
        .toList
        .asserting(
          _ shouldBe List(
            Vector(mockBandit("f1"))
          )
        )
    }

    "new value when the bandit settings changes" in {
      implicit val cbm = mockCbm(
        Vector(mockBandit("f1")),
        Vector(mockBandit("f1", eventChunkSize = 4))
      )
      ConversionBanditUpdater
        .runningBandits[IO](25.milliseconds)
        .interruptAfter(75.milliseconds)
        .compile
        .toList
        .asserting(
          _ shouldBe List(
            Vector(mockBandit("f1")),
            Vector(mockBandit("f1", eventChunkSize = 4))
          )
        )
    }

    "new value when added new bandit" in {
      implicit val cbm = mockCbm(
        Vector(mockBandit("f1")),
        Vector(mockBandit("f1"), mockBandit("f2"))
      )
      ConversionBanditUpdater
        .runningBandits[IO](25.milliseconds)
        .interruptAfter(75.milliseconds)
        .compile
        .toList
        .asserting(
          _ shouldBe List(
            Vector(mockBandit("f1")),
            Vector(mockBandit("f1"), mockBandit("f2"))
          )
        )
    }

    "new value when added removed bandit" in {
      implicit val cbm = mockCbm(
        Vector(mockBandit("f1"), mockBandit("f2")),
        Vector(mockBandit("f1"))
      )
      ConversionBanditUpdater
        .runningBandits[IO](25.milliseconds)
        .interruptAfter(75.milliseconds)
        .compile
        .toList
        .asserting(
          _ shouldBe List(
            Vector(mockBandit("f1"), mockBandit("f2")),
            Vector(mockBandit("f1"))
          )
        )
    }

    "new value when groups changed removed bandit" in {
      implicit val cbm = mockCbm(
        Vector(mockBandit("f1", groups = List("A", "B"))),
        Vector(mockBandit("f1", groups = List("A", "B", "C")))
      )
      ConversionBanditUpdater
        .runningBandits[IO](25.milliseconds)
        .interruptAfter(75.milliseconds)
        .compile
        .toList
        .asserting(
          _ shouldBe List(
            Vector(mockBandit("f1", groups = List("A", "B"))),
            Vector(mockBandit("f1", groups = List("A", "B", "C")))
          )
        )
    }

    "no new value when groups merely changed sequence" in {
      implicit val cbm = mockCbm(
        Vector(mockBandit("f1", groups = List("A", "B"))),
        Vector(mockBandit("f1", groups = List("B", "A")))
      )
      ConversionBanditUpdater
        .runningBandits[IO](25.milliseconds)
        .interruptAfter(75.milliseconds)
        .compile
        .toList
        .asserting(
          _ shouldBe List(
            Vector(mockBandit("f1", groups = List("A", "B")))
          )
        )
    }

  }
}
