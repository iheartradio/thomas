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
import com.iheart.thomas.bandit.bayesian.{BayesianState, ConversionBMABAlg}
import com.iheart.thomas.mongo
import com.typesafe.config.ConfigFactory
import org.scalatest.Matchers
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scanamo.LocalDynamoDB
import cats.tagless.implicits._
import cats.~>
import scalacache.CatsEffect.modes._
import com.iheart.thomas.abtest.`package`.APIResult
import lihua.EntityDAO
import play.api.libs.json.{JsObject, Json}
import cats.implicits._
import com.amazonaws.services.dynamodbv2.model.ResourceInUseException
import com.stripe.rainier.sampler.RNG

import concurrent.duration._
class ConversionBMABAlgSuite extends AnyFunSuiteLike with Matchers {

  import concurrent.ExecutionContext.Implicits.global

  import mongo.idSelector
  implicit val config = ConfigFactory.load()
  val fk = λ[APIResult[IO, ?] ~> IO](_.leftWiden[Throwable].rethrowT)

  lazy val mangoDAOsR = mongo.daosResource[IO].flatMap {
    case daos =>
      Resource
        .make(IO.pure(daos)) {
          case (abtestDAO, featureDAO, kpiDAO) =>
            List(abtestDAO, featureDAO, kpiDAO)
              .traverse(_.removeAll(Json.obj()))
              .leftWiden[Throwable]
              .rethrowT
              .void
        }
  }

  lazy val stateDAOR = Resource.make {
    IO.delay(LocalDynamoDB.client())
      .flatTap { client =>
        import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
        IO.delay(LocalDynamoDB.createTable(client)(dynamo.DAOs.banditStateTableName)(
            lihua.idFieldName -> S))
          .void
          .recover {
            case e: ResourceInUseException => () //happens when table already exisits
          }
      }
      .map { implicit amzClt =>
        dynamo.DAOs.stateDAO[IO]
      }
  } { stateDAO =>
    stateDAO.removeAll().void
  }

  /**
    * An ConversionAPI resource that cleans up after
    */
  lazy val apiR = (mangoDAOsR, stateDAOR).tupled
    .map {
      case (daos, sd) =>
        implicit val (abtestDAO, featureDAO, kpiDAO) = daos
        implicit val stateDAO = sd
        lazy val ttl = 0.seconds

        implicit val kpi = implicitly[KPIApi[APIResult[IO, ?]]].mapK(fk)
        implicit val abtestAlg = {
          val int: AbtestAlg[APIResult[IO, ?]] =
            new DefaultAbtestAlg[APIResult[IO, ?]](ttl)
          int.mapK(fk)
        }

        implicit val ss = SampleSettings.default
        implicit val rng = RNG.default

        (ConversionBMABAlg.default[IO](implicitly, kpi, abtestAlg), kpi)

    }

  def withAPI[A](f: (ConversionBMABAlg[IO], KPIApi[IO]) => IO[A]): A =
    apiR.use(f.tupled).unsafeRunSync()
  def withAPI[A](f: ConversionBMABAlg[IO] => IO[A]): A = withAPI((api, _) => f(api))

  test("init state") {
    val spec = BanditSpec(List("A", "B"), "A_new_Feature", "test BMAB")
    val (init, currentState) = withAPI { api =>
      for {
        is <- api.init(spec, "Test Runner", Instant.now)
        current <- api.currentState(spec.feature)
      } yield (is, current)
    }
    val (_, initState) = init
    initState.arms.size shouldBe 2
    initState.arms.map(_.likelihoodOptimum).forall(_.p == 0) shouldBe true
    initState.spec shouldBe spec
    val (abtest, newState) = currentState
    newState shouldBe initState
    abtest.data.groups.map(_.size) shouldBe List(0.5d, 0.5d)
  }

  test("update state") {
    val spec = BanditSpec(List("A", "B"), "A_new_Feature", "test BMAB")
    val currentState = withAPI { api =>
      for {
        _ <- api.init(spec, "Test Runner", Instant.now)
        _ <- api.updateRewardState(spec.feature,
                                   Map("A" -> Conversions(12, 2),
                                       "B" -> Conversions(43, 6)))
        _ <- api.updateRewardState(spec.feature,
                                   Map("A" -> Conversions(13, 5),
                                       "B" -> Conversions(12, 7)))
        current <- api.currentState(spec.feature)
      } yield current
    }

    val (_, newState) = currentState
    newState.arms.find(_.name == "A").get.rewardState shouldBe Conversions(25, 7)
    newState.arms.find(_.name == "B").get.rewardState shouldBe Conversions(55, 13)

  }

  test("rellocate") {
    val spec = BanditSpec(List("A", "B"), "A_new_Feature", "test BMAB")
    val kpi = BetaKPIDistribution("test kpi", alphaPrior = 1000, betaPrior = 100000)
    val currentState = withAPI { (api, kpiAPI) =>
      for {
        _ <- kpiAPI.upsert(kpi)
        _ <- api.init(spec, "Test Runner", Instant.now)
        _ <- api.updateRewardState(spec.feature,
                                   Map("A" -> Conversions(12, 2),
                                       "B" -> Conversions(43, 10)))
        _ <- api.reallocate(spec.feature, kpi.name)
        current <- api.currentState(spec.feature)
      } yield current
    }

    val (abtest, _) = currentState

    abtest.data.getGroup("B").get.size shouldBe >(abtest.data.getGroup("A").get.size)

  }

}
