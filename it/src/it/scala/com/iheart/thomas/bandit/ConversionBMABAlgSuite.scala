package com.iheart.thomas
package bandit

import java.time.{Instant, OffsetDateTime}

import cats.data.EitherT
import cats.MonadError
import cats.effect.{IO, Resource}
import com.iheart.thomas.abtest.{AbtestAlg, DefaultAbtestAlg}
import com.iheart.thomas.analysis.{
  BetaKPIDistribution,
  Conversions,
  KPIDistributionApi,
  KPIName
}
import com.iheart.thomas.bandit.bayesian.{
  BanditSettings,
  BanditState,
  ConversionBMABAlg
}
import com.iheart.thomas.mongo
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AnyFunSuiteLike
import _root_.play.api.libs.json.{JsObject, Json}
import cats.implicits._
import com.iheart.thomas.abtest.model.Abtest.Specialization.MultiArmBanditConversion
import com.iheart.thomas.abtest.model.{AbtestSpec, Group}
import com.stripe.rainier.sampler.RNG
import lihua.dynamo.testkit.LocalDynamo

import concurrent.duration._

class ConversionBMABAlgSuite extends AnyFunSuiteLike with Matchers {

  import testkit.Resources._

  val kpi = BetaKPIDistribution(
    "test kpi",
    alphaPrior = 1000,
    betaPrior = 100000
  )

  def withAPI[A](
      f: (ConversionBMABAlg[IO], KPIDistributionApi[IO], AbtestAlg[IO]) => IO[A]
    ): A =
    apis
      .use {
        case (conversionBMABAlg, kPIApi, abtestAlg) =>
          kPIApi.upsert(kpi) >> f(conversionBMABAlg, kPIApi, abtestAlg)
      }
      .unsafeRunSync()

  def withAPI[A](f: ConversionBMABAlg[IO] => IO[A]): A =
    withAPI((api, _, _) => f(api))

  def createSpec(
      feature: FeatureName = "A_new_Feature",
      arms: List[ArmName] = List("A", "B"),
      author: String = "Test Runner",
      start: OffsetDateTime = OffsetDateTime.now,
      title: String = "for integration tests",
      kpiName: KPIName = kpi.name,
      minimumSizeChange: Double = 0.01,
      initialSampleSize: Int = 0,
      historyRetention: Option[FiniteDuration] = None
    ) =
    BanditSpec(
      arms = arms,
      start = start,
      settings = BanditSettings(
        feature = feature,
        author = author,
        title = title,
        kpiName = kpiName,
        minimumSizeChange = minimumSizeChange,
        initialSampleSize = initialSampleSize,
        historyRetention = historyRetention,
        maintainExplorationSize = None,
        distSpecificSettings = BanditSettings.Conversion(
          eventChunkSize = 1,
          reallocateEveryNChunk = 1
        )
      )
    )

  test("init state") {
    val spec = createSpec()
    val (init, currentState) = withAPI { api =>
      for {
        is <- api.init(spec)
        current <- api.currentState(spec.feature)
      } yield (is, current)
    }
    init.state.arms.size shouldBe 2
    init.state.arms
      .map(_.likelihoodOptimum)
      .forall(_.p == 0) shouldBe true
    init.settings.title shouldBe spec.settings.title
    init.abtest.data.specialization shouldBe Some(
      MultiArmBanditConversion
    )
    currentState.state shouldBe init.state
    currentState.abtest.data.groups
      .map(_.size) shouldBe List(0.5d, 0.5d)
    currentState.abtest.data.start
      .isBefore(Instant.now.plusSeconds(1))
  }

  test("invalid init should not leave corrupt data") {
    val spec = createSpec(start = OffsetDateTime.now.minusDays(1))
    val (init, r) = withAPI { api =>
      for {
        initialTry <- MonadError[IO, Throwable].attempt(api.init(spec).void)
        r <- api.init(spec.copy(start = OffsetDateTime.now.plusMinutes(1)))
      } yield (initialTry, r)
    }
    init.isLeft shouldBe true
    r.feature shouldBe spec.feature
  }

  test("running bandits include running bandits") {
    val spec = createSpec()

    val spec2 = createSpec(feature = "Another_new_feature")
    val spec3 = createSpec(feature = "Yet_Another_new_feature")

    val regularAb = AbtestSpec(
      name = "test",
      author = "kai",
      feature = "regular_abtest",
      start = OffsetDateTime.now,
      end = None,
      groups = List(Group("A", 0.5), Group("B", 0.5))
    )

    val running = withAPI { (api, _, abtestAlg) =>
      for {
        _ <- api.init(spec)
        _ <- api.init(spec2)
        b3 <- api.init(spec3)
        _ <- abtestAlg.create(regularAb, false)
        _ <- abtestAlg.terminate(b3.abtest._id)
        _ <- timer.sleep(200.milliseconds)
        running <- api.runningBandits()
      } yield running
    }
    running.map(_.abtest.data.feature).toSet shouldBe Set(
      spec.feature,
      spec2.feature
    )
  }

  test("update state") {
    val spec = createSpec()

    val currentState = withAPI { api =>
      for {
        _ <- api.init(spec)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(2, 12),
            "B" -> Conversions(6, 43)
          )
        )
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(5, 13),
            "B" -> Conversions(7, 12)
          )
        )
        current <- api.currentState(spec.feature)
      } yield current
    }

    val newState = currentState.state
    newState.arms
      .find(_.name == "A")
      .get
      .rewardState shouldBe Conversions(7, 25)
    newState.arms
      .find(_.name == "B")
      .get
      .rewardState shouldBe Conversions(13, 55)

  }

  test("reallocate update the state with latest possibilities") {
    val spec = createSpec()

    val currentState = withAPI { api =>
      for {
        _ <- api.init(spec)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(2, 12),
            "B" -> Conversions(10, 43)
          )
        )
        _ <- api.reallocate(spec.feature)
        current <- api.currentState(spec.feature)
      } yield current
    }

    currentState.state.getArm("B").get.likelihoodOptimum.p shouldBe >(
      currentState.state.getArm("A").get.likelihoodOptimum.p
    )

  }

  test("reallocate clean up last tests") {
    val spec = createSpec(
      historyRetention = Some(50.milliseconds)
    )

    val currentTests = withAPI { (api, _, abtestAlg) =>
      for {
        _ <- api.init(spec)
        _ <- api.reallocate(spec.feature)
        _ <- api.reallocate(spec.feature)
        _ <- api.reallocate(spec.feature)
        _ <- timer.sleep(300.milliseconds)
        _ <- api.reallocate(spec.feature)
        tests <- abtestAlg.getTestsByFeature(spec.feature)

      } yield tests
    }

    currentTests.size should be < (3)

  }

  test("reallocate reallocate the size of the abtest groups") {
    val spec = createSpec(
      )

    val currentState = withAPI { api =>
      for {
        _ <- api.init(spec)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(2, 12),
            "B" -> Conversions(10, 43)
          )
        )
        _ <- api.reallocate(spec.feature)
        current <- api.currentState(spec.feature)
      } yield current
    }

    currentState.abtest.data
      .getGroup("B")
      .get
      .size shouldBe >(
      currentState.abtest.data.getGroup("A").get.size
    )

  }

  test("reallocate does not reallocate groups until it hits enough samples") {
    val spec = createSpec(
      minimumSizeChange = 0.0001,
      initialSampleSize = 100
    )

    val (init, before, after) = withAPI { api =>
      for {
        is <- api.init(spec)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(2, total = 99),
            "B" -> Conversions(1, total = 5)
          )
        )
        _ <- api.reallocate(spec.feature)
        beforeHittingMinmumSampleSize <- api.currentState(spec.feature)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(3, total = 5),
            "B" -> Conversions(3, total = 97)
          )
        )
        _ <- api.reallocate(spec.feature)
        afterHittingMinmumSampleSize <- api.currentState(spec.feature)
      } yield (
        is.abtest,
        beforeHittingMinmumSampleSize.abtest,
        afterHittingMinmumSampleSize.abtest
      )
    }

    before shouldBe init
    after should not be (init)

  }

  test(
    "reallocate does not reallocate groups if the new group size remains the same"
  ) {
    val spec = createSpec(
      minimumSizeChange = 0.02
    )

    val (first, second) = withAPI { api =>
      for {
        is <- api.init(spec)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(1000, total = 9000),
            "B" -> Conversions(500, total = 5000)
          )
        )
        _ <- api.reallocate(spec.feature)
        firstReallocate <- api.currentState(spec.feature)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(200, total = 1800), //the new samples are the same rate as the old one.
            "B" -> Conversions(300, total = 3000)
          )
        )
        _ <- api.reallocate(spec.feature)
        secondReallocate <- api.currentState(spec.feature)
      } yield (
        firstReallocate.abtest,
        secondReallocate.abtest
      )
    }

    first shouldBe second

  }

}
