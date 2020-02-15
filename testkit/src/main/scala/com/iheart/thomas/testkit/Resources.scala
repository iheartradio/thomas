package com.iheart.thomas
package testkit

import java.time.Instant

import cats.effect.{IO, Resource}
import cats.implicits._
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.analysis.{Conversions, KPIDistributionApi, SampleSettings}
import com.iheart.thomas.bandit.bayesian.ConversionBMABAlg
import com.iheart.thomas.bandit.tracking.EventLogger
import com.iheart.thomas.{dynamo, mongo}
import com.stripe.rainier.sampler.RNG
import com.typesafe.config.ConfigFactory
import lihua.dynamo.testkit.LocalDynamo
import _root_.play.api.libs.json.Json
import com.iheart.thomas.bandit.bayesian.StateDAO
import dynamo.DynamoFormats._
import scala.concurrent.duration._

object Resources {

  import mongo.idSelector

  import concurrent.ExecutionContext.Implicits.global
  implicit private val cs = IO.contextShift(global)
  val timer = IO.timer(global)
  implicit private val _timer = timer

  lazy val mangoDAOs =
    Resource.liftF(IO(ConfigFactory.load(getClass.getClassLoader))).flatMap {
      config =>
        mongo.daosResource[IO](config).flatMap {
          case daos =>
            Resource
              .make(IO.pure(daos)) {
                case (abtestDAO, featureDAO, kpiDAO) =>
                  List(abtestDAO, featureDAO, kpiDAO)
                    .traverse(_.removeAll(Json.obj()))
                    .void
              }
        }
    }

  lazy val localDynamoR =
    LocalDynamo
      .clientWithTables[IO](
        dynamo.DAOs.banditStateTableName -> dynamo.DAOs.banditStateKeys
      )

  lazy val stateDAO: Resource[IO, StateDAO[IO, Conversions]] =
    localDynamoR
      .map { implicit client =>
        dynamo.DAOs.banditState[IO, Conversions]
      }

  /**
    * An ConversionAPI resource that cleans up after
    */
  lazy val apis: Resource[
    IO,
    (ConversionBMABAlg[IO], KPIDistributionApi[IO], AbtestAlg[IO])
  ] =
    (mangoDAOs, stateDAO).tupled
      .flatMap {
        case (daos, sd) =>
          implicit val (abtestDAO, featureDAO, kpiDAO) = daos
          lazy val refreshPeriod = 0.seconds
          implicit val isd = sd

          implicit val logger = EventLogger.noop[IO]
          AbtestAlg.defaultResource[IO](refreshPeriod).map { implicit abtestAlg =>
            implicit val ss = SampleSettings.default
            implicit val rng = RNG.default
            implicit val nowF = IO.delay(Instant.now)

            (
              ConversionBMABAlg.default[IO],
              KPIDistributionApi.default[IO],
              abtestAlg
            )
          }
      }

}
