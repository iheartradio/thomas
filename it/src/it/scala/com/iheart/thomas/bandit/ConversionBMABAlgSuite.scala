package com.iheart.thomas
package bandit

import java.time.Instant

import cats.data.EitherT
import cats.effect.{IO, Resource}
import com.iheart.thomas.abtest.{AbtestAlg, DefaultAbtestAlg}
import com.iheart.thomas.analysis.{
  BetaKPIDistribution,
  Conversions,
  KPIApi,
  SampleSettings
}
import com.iheart.thomas.bandit.bayesian.{BanditState, ConversionBMABAlg}
import com.iheart.thomas.mongo
import com.typesafe.config.ConfigFactory
import org.scalatest.Matchers
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scanamo.LocalDynamoDB
import cats.tagless.implicits._
import cats.~>
import lihua.EntityDAO
import play.api.libs.json.{JsObject, Json}
import cats.implicits._
import com.amazonaws.services.dynamodbv2.model.ResourceInUseException
import com.stripe.rainier.sampler.RNG
import lihua.dynamo.ScanamoEntityDAO

import concurrent.duration._
class ConversionBMABAlgSuite extends AnyFunSuiteLike with Matchers {

  import concurrent.ExecutionContext.Implicits.global

  import mongo.idSelector
  implicit val config = ConfigFactory.load()

  lazy val mangoDAOsR = mongo.daosResource[IO].flatMap {
    case daos =>
      Resource
        .make(IO.pure(daos)) {
          case (abtestDAO, featureDAO, kpiDAO) =>
            List(abtestDAO, featureDAO, kpiDAO)
              .traverse(_.removeAll(Json.obj()))
              .void
        }
  }

  lazy val stateDAOR =
    Resource
      .make {
        IO.delay(LocalDynamoDB.client())
          .flatTap { client =>
            ScanamoEntityDAO
              .ensureTable[IO](client, dynamo.DAOs.banditStateTableName)
          }
          .map { amzClt =>
            dynamo.DAOs.lihuaStateDAO[IO](amzClt)
          }
      } { stateDAO =>
        stateDAO.removeAll().void
      }
      .map(sd => BanditStateDAO.bayesianfromLihua(sd))

  /**
    * An ConversionAPI resource that cleans up after
    */
  lazy val apiR = (mangoDAOsR, stateDAOR).tupled
    .flatMap {
      case (daos, sd) =>
        implicit val (abtestDAO, featureDAO, kpiDAO) = daos
        lazy val refreshPeriod = 0.seconds
        implicit val cs = IO.contextShift(global)
        implicit val timer = IO.timer(global)
        val kpi = implicitly[KPIApi[IO]]

        AbtestAlg.defaultResource[IO](refreshPeriod).map { abtestAlg =>
          implicit val ss = SampleSettings.default
          implicit val rng = RNG.default

          (ConversionBMABAlg.default[IO](sd, kpi, abtestAlg), kpi)

        }

    }

  def withAPI[A](f: (ConversionBMABAlg[IO], KPIApi[IO]) => IO[A]): A =
    apiR.use(f.tupled).unsafeRunSync()
  def withAPI[A](f: ConversionBMABAlg[IO] => IO[A]): A = withAPI((api, _) => f(api))

  test("init state") {
    val spec = BanditSpec(feature = "A_new_Feature",
                          arms = List("A", "B"),
                          author = "Test Runner",
                          start = Instant.now,
                          title = "for integration tests")

    val (init, currentState) = withAPI { api =>
      for {
        is <- api.init(spec)
        current <- api.currentState(spec.feature)
      } yield (is, current)
    }
    init.state.arms.size shouldBe 2
    init.state.arms.map(_.likelihoodOptimum).forall(_.p == 0) shouldBe true
    init.state.title shouldBe spec.title
    currentState.state shouldBe init.state
    currentState.abtest.data.groups.map(_.size) shouldBe List(0.5d, 0.5d)
  }

  test("update state") {
    val spec = BanditSpec(feature = "A_new_Feature",
                          arms = List("A", "B"),
                          author = "Test Runner",
                          start = Instant.now,
                          title = "for initegration tests")

    val currentState = withAPI { api =>
      for {
        _ <- api.init(spec)
        _ <- api.updateRewardState(spec.feature,
                                   Map("A" -> Conversions(12, 2),
                                       "B" -> Conversions(43, 6)))
        _ <- api.updateRewardState(spec.feature,
                                   Map("A" -> Conversions(13, 5),
                                       "B" -> Conversions(12, 7)))
        current <- api.currentState(spec.feature)
      } yield current
    }

    val newState = currentState.state
    newState.arms.find(_.name == "A").get.rewardState shouldBe Conversions(25, 7)
    newState.arms.find(_.name == "B").get.rewardState shouldBe Conversions(55, 13)

  }

  test("reallocate") {
    val spec = BanditSpec(feature = "A_new_Feature",
                          arms = List("A", "B"),
                          author = "Test Runner",
                          start = Instant.now,
                          title = "for integration tests")

    val kpi = BetaKPIDistribution("test kpi", alphaPrior = 1000, betaPrior = 100000)
    val currentState = withAPI { (api, kpiAPI) =>
      for {
        _ <- kpiAPI.upsert(kpi)
        _ <- api.init(spec)
        _ <- api.updateRewardState(spec.feature,
                                   Map("A" -> Conversions(12, 2),
                                       "B" -> Conversions(43, 10)))
        _ <- api.reallocate(spec.feature, kpi.name)
        current <- api.currentState(spec.feature)
      } yield current
    }

    currentState.abtest.data.getGroup("B").get.size shouldBe >(
      currentState.abtest.data.getGroup("A").get.size)

  }

}
