package com.iheart.thomas
package bandit

import cats.MonadError
import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.abtest.model.Abtest.Specialization.MultiArmBandit
import com.iheart.thomas.abtest.model.{AbtestSpec, Group}
import com.iheart.thomas.analysis.bayesian.models.BetaModel
import com.iheart.thomas.analysis.monitor.{ExperimentKPIState, ExperimentKPIStateDAO}
import com.iheart.thomas.analysis.{ConversionKPI, Conversions, KPIName, KPIRepo}
import com.iheart.thomas.bandit.bayesian.{BanditSpec, BayesianMAB, BayesianMABAlg}
import com.iheart.thomas.utils.time.Period
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers

import java.time.{Instant, OffsetDateTime}
import scala.concurrent.duration._

class BayesianMABAlgITSuite extends BayesianMABAlgITSuiteBase {

  test("init state") {
    val spec = createSpec()
    val (init, get) = withAPI { api =>
      for {
        is <- api.init(spec)
        current <- api.get(spec.feature)
      } yield (is, current)
    }
    init.state should be(empty)
    init.spec.title shouldBe spec.title
    init.abtest.data.specialization shouldBe Some(
      MultiArmBandit
    )

    get.state should be(empty)
    get.abtest.data.groups
      .map(_.size) shouldBe List(0.5d, 0.5d)
    get.abtest.data.start
      .isBefore(Instant.now.plusSeconds(1))
  }

  test("invalid init should not leave corrupt data") {
    val spec = createSpec(arms = Seq(ArmSpec("A", Some(1.1))))
    val (init, r) = withAPI { api =>
      for {
        initialTry <- MonadError[IO, Throwable].attempt(api.init(spec).void)
        r <- api.init(createSpec(arms = Seq(ArmSpec("A", Some(0.3)))))
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

    val running = withAPI { (api, _, abtestAlg, _) =>
      for {
        _ <- api.init(spec)
        _ <- api.init(spec2)
        b3 <- api.init(spec3)
        _ <- abtestAlg.create(regularAb, false)
        _ <- abtestAlg.terminate(b3.abtest._id)
        _ <- IO.sleep(200.milliseconds)
        running <- api.getAll
      } yield running
    }
    running.map(_.abtest.data.feature).toSet shouldBe Set(
      spec.feature,
      spec2.feature
    )
  }

  test("updatePolicy update the state with latest possibilities") {
    val spec = createSpec()

    val get = withAPI { (api, sDao) =>
      for {
        b <- api.init(spec)
        s <- state(
          b,
          Map(
            "A" -> Conversions(2, 12),
            "B" -> Conversions(10, 43)
          ),
          sDao
        )
        _ <- api.updatePolicy(s)
        current <- api.get(spec.feature)
      } yield current
    }

    get.state.get.getArm("B").get.likelihoodOptimum.get.p shouldBe >(
      get.state.get.getArm("A").get.likelihoodOptimum.get.p
    )

  }

  test("updatePolicy clean up last tests") {
    val spec = createSpec(
      historyRetention = Some(50.milliseconds)
    )

    val currentTests = withAPI { (api, _, abtestAlg, sdao) =>
      for {
        b <- api.init(spec)
        s <- state(
          b,
          Map(
            "A" -> Conversions(2, 12),
            "B" -> Conversions(10, 43)
          ),
          sdao
        )
        _ <- api.updatePolicy(s)
        _ <- api.updatePolicy(s)
        _ <- api.updatePolicy(s)
        _ <- IO.sleep(300.milliseconds)
        _ <- api.updatePolicy(s)
        tests <- abtestAlg.getTestsByFeature(spec.feature)

      } yield tests
    }

    currentTests.size should be < (3)

  }

  test("updatePolicy resizes abtest groups") {
    val spec = createSpec()

    val get = withAPI { (api, sdao) =>
      for {
        b <- api.init(spec)

        s <- state(
          b,
          Map(
            "A" -> Conversions(2, 12),
            "B" -> Conversions(10, 43)
          ),
          sdao
        )
        _ <- api.updatePolicy(s)
        current <- api.get(spec.feature)
      } yield current
    }

    get.abtest.data
      .getGroup("B")
      .get
      .size shouldBe >(
      get.abtest.data.getGroup("A").get.size
    )

  }

  test("updatePolicy does not reallocate groups until it hits enough samples") {
    val spec = createSpec(
      minimumSizeChange = 0.0001,
      initialSampleSize = 100
    )

    val (init, before, after) = withAPI { (api, sdao) =>
      for {
        is <- api.init(spec)
        s <- state(
          is,
          Map(
            "A" -> Conversions(2, total = 99),
            "B" -> Conversions(1, total = 5)
          ),
          sdao
        )
        _ <- api.updatePolicy(s)
        beforeHittingMinmumSampleSize <- api.get(spec.feature)
        s2 <- state(
          is,
          Map(
            "A" -> Conversions(3, total = 103),
            "B" -> Conversions(3, total = 104)
          ),
          sdao
        )
        _ <- api.updatePolicy(s2)
        afterHittingMinmumSampleSize <- api.get(spec.feature)
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

    val (first, second) = withAPI { (api, sdao) =>
      for {
        b <- api.init(spec)
        s <- state(
          b,
          Map(
            "A" -> Conversions(1000, total = 9000),
            "B" -> Conversions(500, total = 5000)
          ),
          sdao
        )
        _ <- api.updatePolicy(s)
        firstupdatePolicy <- api.get(spec.feature)
        s2 <- state(
          b,
          Map(
            "A" -> Conversions(
              2000,
              total = 18000
            ), // the new samples are the same rate as the old one.
            "B" -> Conversions(800, total = 8000)
          ),
          sdao
        )
        _ <- api.updatePolicy(s2)
        secondupdatePolicy <- api.get(spec.feature)
      } yield (
        firstupdatePolicy.abtest,
        secondupdatePolicy.abtest
      )
    }

    first shouldBe second

  }

  test("updatePolicy ignores reserved groups") {
    val spec = createSpec(
      arms = List(
        ArmSpec("A"),
        ArmSpec("B"),
        ArmSpec("C", initialSize = Some(0.3), reserved = true)
      )
    )

    val current = withAPI { (api, sdao) =>
      for {
        b <- api.init(spec)
        s <- state(
          b,
          Map(
            "A" -> Conversions(100, 500),
            "B" -> Conversions(100, 500),
            "C" -> Conversions(500, 500)
          ),
          sdao
        )
        _ <- api.updatePolicy(s)
        current <- api.get(spec.feature)
      } yield current
    }

    current.state.get.arms
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

class BayesianMABAlgITSuiteBase extends AnyFunSuiteLike with Matchers {

  import testkit.Resources._

//  implicit val logger: EventLogger[IO] = EventLogger.stdout
  val kpi = ConversionKPI(
    KPIName("test_kpi"),
    "kai",
    None,
    BetaModel(alpha = 1000, beta = 100000),
    None
  )

  def withAPI[A](
      f: (
          BayesianMABAlg[IO],
          KPIRepo[IO, ConversionKPI],
          AbtestAlg[IO],
          ExperimentKPIStateDAO[IO, Conversions]
      ) => IO[A]
    ): A = {
    import cats.effect.unsafe.implicits.global
    apis
      .use { case (alg, abtestAlg, conversionKPIAlg, conversionStateDAO) =>
        conversionKPIAlg
          .create(kpi) >> f(alg, conversionKPIAlg, abtestAlg, conversionStateDAO)
      }
      .unsafeRunSync()
  }

  def withAPI[A](f: BayesianMABAlg[IO] => IO[A]): A =
    withAPI((api, _, _, _) => f(api))

  def withAPI[A](
      f: (BayesianMABAlg[IO], ExperimentKPIStateDAO[IO, Conversions]) => IO[A]
    ): A =
    withAPI((api, _, _, dao) => f(api, dao))

  def state(
      bandit: BayesianMAB,
      stats: Map[ArmName, Conversions],
      dao: ExperimentKPIStateDAO[IO, Conversions]
    ): IO[ExperimentKPIState[Conversions]] = {
    val data = (
      NonEmptyList.fromListUnsafe(stats.toList.map { case (an, c) =>
        ArmState(an, c, None)
      }),
      Period(Instant.now, Instant.now)
    )
    dao.upsert(bandit.spec.stateKey)((_, _) => data)(data)
  }

  def createSpec(
      feature: FeatureName = "A_new_Feature",
      arms: Seq[ArmSpec] = List(ArmSpec("A"), ArmSpec("B")),
      author: String = "Test Runner",
      title: String = "for integration tests",
      kpiName: KPIName = kpi.name,
      minimumSizeChange: Double = 0.01,
      initialSampleSize: Int = 0,
      historyRetention: Option[FiniteDuration] = None
    ) = BanditSpec(
    feature = feature,
    author = author,
    title = title,
    kpiName = kpiName,
    arms = arms,
    minimumSizeChange = minimumSizeChange,
    initialSampleSize = initialSampleSize,
    historyRetention = historyRetention,
    stateMonitorEventChunkSize = 1
  )

}
