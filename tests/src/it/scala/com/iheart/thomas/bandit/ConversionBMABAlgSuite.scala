package com.iheart.thomas
package bandit

import java.time.{Instant, OffsetDateTime}

import cats.data.EitherT
import cats.MonadError
import cats.effect.{IO, Resource}
import com.iheart.thomas.abtest.{AbtestAlg, DefaultAbtestAlg}
import com.iheart.thomas.analysis.{
  BetaKPIModel,
  Conversions,
  KPIModelApi,
  KPIName,
  Probability
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
import com.iheart.thomas.abtest.model.Abtest.Specialization.MultiArmBandit
import com.iheart.thomas.abtest.model.{AbtestSpec, Group}
import com.iheart.thomas.bandit.tracking.EventLogger
import com.iheart.thomas.testkit.Resources.timer
import com.stripe.rainier.sampler.RNG

import concurrent.duration._

class ConversionBMABAlgSuite extends ConversionBMABAlgSuiteBase {

  import testkit.Resources._

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
      .forall(_.isEmpty) shouldBe true
    init.settings.title shouldBe spec.settings.title
    init.abtest.data.specialization shouldBe Some(
      MultiArmBandit
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
      groups = List(Group("A", 0.5, None), Group("B", 0.5, None))
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

  test("updatePolicy update the state with latest possibilities") {
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
        _ <- api.updatePolicy(spec.feature)
        current <- api.currentState(spec.feature)
      } yield current
    }

    currentState.state.getArm("B").get.likelihoodOptimum.get.p shouldBe >(
      currentState.state.getArm("A").get.likelihoodOptimum.get.p
    )

  }

  test("updatePolicy clean up last tests") {
    val spec = createSpec(
      historyRetention = Some(50.milliseconds)
    )

    val currentTests = withAPI { (api, _, abtestAlg) =>
      for {
        _ <- api.init(spec)
        _ <- api.updatePolicy(spec.feature)
        _ <- api.updatePolicy(spec.feature)
        _ <- api.updatePolicy(spec.feature)
        _ <- timer.sleep(300.milliseconds)
        _ <- api.updatePolicy(spec.feature)
        tests <- abtestAlg.getTestsByFeature(spec.feature)

      } yield tests
    }

    currentTests.size should be < (3)

  }

  test("updatePolicy resizes abtest groups") {
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
        _ <- api.updatePolicy(spec.feature)
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

  test("updatePolicy does not reallocate groups until it hits enough samples") {
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
        _ <- api.updatePolicy(spec.feature)
        beforeHittingMinmumSampleSize <- api.currentState(spec.feature)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(3, total = 5),
            "B" -> Conversions(3, total = 97)
          )
        )
        _ <- api.updatePolicy(spec.feature)
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
    "updatePolicy does not updatePolicy groups if the new group size remains the same"
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
        _ <- api.updatePolicy(spec.feature)
        firstupdatePolicy <- api.currentState(spec.feature)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(
              200,
              total = 1800
            ), //the new samples are the same rate as the old one.
            "B" -> Conversions(300, total = 3000)
          )
        )
        _ <- api.updatePolicy(spec.feature)
        secondupdatePolicy <- api.currentState(spec.feature)
      } yield (
        firstupdatePolicy.abtest,
        secondupdatePolicy.abtest
      )
    }

    first shouldBe second

  }

  test("updatePolicy update iterations") {
    val spec = createSpec(iterationDuration = Some(10.milliseconds))

    val (previousState, currentState) = withAPI { api =>
      for {
        _ <- api.init(spec)
        previous <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(2, 12),
            "B" -> Conversions(10, 43)
          )
        )

        _ <- timer.sleep(50.milliseconds)
        _ <- api.updatePolicy(spec.feature)
        current <- api.currentState(spec.feature)
      } yield (previous, current.state)
    }

    currentState.historical shouldBe Some(
      previousState.rewardState
    )
    currentState.arms.size shouldBe previousState.arms.size
    currentState.arms.forall(_.rewardState.total == 0) shouldBe true
    currentState.iterationStart
      .isAfter(previousState.iterationStart) shouldBe true

  }

  test("updatePolicy use last iteration as prior") {
    val spec = createSpec(iterationDuration = Some(400.milliseconds))

    val currentState = withAPI { api =>
      for {
        _ <- api.init(spec)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(20, 500),
            "B" -> Conversions(100, 500)
          )
        )
        _ <- timer.sleep(500.milliseconds) //one iteration

        _ <- api.updatePolicy(spec.feature)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(100, 500),
            "B" -> Conversions(20, 500)
          )
        )
        current <- api.updatePolicy(spec.feature)
      } yield current.state
    }

    currentState.arms
      .find(_.name == "B")
      .get
      .likelihoodOptimum
      .get
      .p shouldBe 0.5d +- 0.1d

  }

  test(
    "updatePolicy use forgets data from two iterations ago without oldHistoryWeight"
  ) {
    val spec = createSpec(iterationDuration = Some(20.milliseconds))

    val currentPolicy = withAPI { api =>
      for {
        _ <- api.init(spec)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(400, 500),
            "B" -> Conversions(1, 500)
          )
        )
        _ <- timer.sleep(30.milliseconds) //one iteration

        _ <- api.updatePolicy(spec.feature)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(1, 500),
            "B" -> Conversions(20, 500)
          )
        )
        _ <- timer.sleep(30.milliseconds) //second iteration

        _ <- api.updatePolicy(spec.feature)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(1, 500),
            "B" -> Conversions(20, 500)
          )
        )
        current <- api.updatePolicy(spec.feature)
      } yield current.abtest.data
    }

    currentPolicy.getGroup("B").get.size shouldBe 1d
  }

  test("updatePolicy keeps data from two iterations ago using old history weight") {
    val spec = createSpec(
      iterationDuration = Some(20.milliseconds),
      oldHistoryWeight = Some(0.4)
    )

    val currentState = withAPI { api =>
      for {
        _ <- api.init(spec)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(400, 500),
            "B" -> Conversions(10, 500)
          )
        )
        _ <- timer.sleep(30.milliseconds) //one iteration

        _ <- api.updatePolicy(spec.feature)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(25, 500),
            "B" -> Conversions(20, 500)
          )
        )
        _ <- timer.sleep(30.milliseconds) //second iteration

        current <- api.updatePolicy(spec.feature)
      } yield current.state
    }

    currentState.historical.get("B").total shouldBe 500
    currentState.historical.get("B").converted shouldBe 16
    currentState.historical.get("A").converted shouldBe 175
  }

  test("initialSampleSize guard includes historical data") {
    val spec = createSpec(
      iterationDuration = Some(20.milliseconds),
      initialSampleSize = 100
    )

    val (firstAbtest, currentAbteset) = withAPI { api =>
      for {
        _ <- api.init(spec)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(40, 101),
            "B" -> Conversions(10, 102)
          )
        )
        _ <- timer.sleep(30.milliseconds) //one iteration

        first <- api.updatePolicy(spec.feature)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(15, 30),
            "B" -> Conversions(10, 20)
          )
        )

        current <- api.updatePolicy(spec.feature)
      } yield (first.abtest.data, current.abtest.data)
    }

    firstAbtest.groups.toSet should not be (currentAbteset.groups.toSet)
  }

  test("updatePolicy ignores reserved groups") {
    val spec = createSpec(
      arms = List(ArmSpec("A"), ArmSpec("B"), ArmSpec("C", initialSize = Some(0.3))),
      reservedGroups = Set("C")
    )

    val current = withAPI { api =>
      for {
        _ <- api.init(spec)
        _ <- api.updateRewardState(
          spec.feature,
          Map(
            "A" -> Conversions(100, 500),
            "B" -> Conversions(100, 500),
            "C" -> Conversions(500, 500)
          )
        )
        _ <- api.updatePolicy(spec.feature)
        current <- api.updatePolicy(spec.feature)
      } yield current
    }

    current.state.arms
      .find(_.name == "C")
      .get
      .likelihoodOptimum shouldBe None

    current.abtest.data.groups.find(_.name == "C").get.size shouldBe BigDecimal(0.3)
    current.abtest.data.groups.find(_.name == "B").get.size.toDouble should be(
      0.35d +- 0.02d
    )
    current.abtest.data.groups.find(_.name == "A").get.size.toDouble should be(
      0.35d +- 0.02d
    )

    current.abtest.data.groups.map(_.size).sum shouldBe BigDecimal(1)

  }

}

class ConversionBMABAlgSuiteBase extends AnyFunSuiteLike with Matchers {

  import testkit.Resources._

//  implicit val logger: EventLogger[IO] = EventLogger.stdout
  val kpi = BetaKPIModel(
    "test kpi",
    alphaPrior = 1000,
    betaPrior = 100000
  )

  def withAPI[A](
      f: (ConversionBMABAlg[IO], KPIModelApi[IO], AbtestAlg[IO]) => IO[A]
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
      arms: List[ArmSpec] = List(ArmSpec("A"), ArmSpec("B")),
      author: String = "Test Runner",
      start: OffsetDateTime = OffsetDateTime.now,
      title: String = "for integration tests",
      kpiName: KPIName = kpi.name,
      minimumSizeChange: Double = 0.01,
      initialSampleSize: Int = 0,
      historyRetention: Option[FiniteDuration] = None,
      iterationDuration: Option[FiniteDuration] = None,
      oldHistoryWeight: Option[Weight] = None,
      reservedGroups: Set[GroupName] = Set.empty
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
        iterationDuration = iterationDuration,
        oldHistoryWeight = oldHistoryWeight,
        reservedGroups = reservedGroups,
        distSpecificSettings = BanditSettings.Conversion(
          eventChunkSize = 1,
          updatePolicyEveryNChunk = 1
        )
      )
    )

}
