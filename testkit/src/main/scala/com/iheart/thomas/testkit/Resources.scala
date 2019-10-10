package com.iheart.thomas.testkit

import java.time.OffsetDateTime

import cats.effect.{IO, Resource}
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.analysis.{Conversions, KPIApi, SampleSettings}
import com.iheart.thomas.bandit.BanditStateDAO
import com.iheart.thomas.bandit.bayesian.{BanditState, ConversionBMABAlg}
import com.iheart.thomas.{dynamo, mongo}
import com.stripe.rainier.sampler.RNG
import com.typesafe.config.ConfigFactory
import lihua.dynamo.testkit.LocalDynamo
import play.api.libs.json.Json
import cats.implicits._

import concurrent.duration._

object Resources {

  import concurrent.ExecutionContext.Implicits.global

  import mongo.idSelector
  implicit private val config = ConfigFactory.load()
  implicit private val cs = IO.contextShift(global)
  val timer = IO.timer(global)
  implicit private val _timer = timer

  lazy val mangoDAOs = mongo.daosResource[IO].flatMap {
    case daos =>
      Resource
        .make(IO.pure(daos)) {
          case (abtestDAO, featureDAO, kpiDAO) =>
            List(abtestDAO, featureDAO, kpiDAO)
              .traverse(_.removeAll(Json.obj()))
              .void
        }
  }

  lazy val stateDAO
      : Resource[IO, BanditStateDAO[IO, BanditState[Conversions]]] =
    LocalDynamo
      .clientWithTables[IO](dynamo.DAOs.banditStateTableName)
      .map { client =>
        BanditStateDAO.bayesianfromLihua(
          dynamo.DAOs.lihuaStateDAO[IO](client)
        )
      }

  /**
    * An ConversionAPI resource that cleans up after
    */
  lazy val apis
      : Resource[IO, (ConversionBMABAlg[IO], KPIApi[IO], AbtestAlg[IO])] =
    (mangoDAOs, stateDAO).tupled
      .flatMap {
        case (daos, sd) =>
          implicit val (abtestDAO, featureDAO, kpiDAO) = daos
          lazy val refreshPeriod = 0.seconds

          val kpi = implicitly[KPIApi[IO]]

          AbtestAlg.defaultResource[IO](refreshPeriod).map { abtestAlg =>
            implicit val ss = SampleSettings.default
            implicit val rng = RNG.default
            implicit val nowF = IO.delay(OffsetDateTime.now)

            (
              ConversionBMABAlg.default[IO](sd, kpi, abtestAlg),
              kpi,
              abtestAlg
            )
          }
      }

}
