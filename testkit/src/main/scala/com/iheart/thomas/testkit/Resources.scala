package com.iheart.thomas
package testkit

import java.time.Instant

import cats.effect.{IO, Resource}
import cats.implicits._
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.analysis.{Conversions, KPIModelApi}
import com.iheart.thomas.bandit.bayesian.{
  BanditSettings,
  BanditSettingsDAO,
  ConversionBMABAlg,
  StateDAO
}
import com.iheart.thomas.bandit.tracking.EventLogger
import com.iheart.thomas.{dynamo, mongo}
import com.stripe.rainier.sampler.{RNG, Sampler}
import com.typesafe.config.ConfigFactory
import lihua.dynamo.testkit.LocalDynamo
import _root_.play.api.libs.json.Json
import dynamo.DynamoFormats._

import scala.concurrent.duration._

object Resources {

  import mongo.idSelector

  import concurrent.ExecutionContext.Implicits.global
  implicit private val cs = IO.contextShift(global)
  val timer = IO.timer(global)
  implicit private val _timer = timer

  val defaultNowF = IO.delay(Instant.now)
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
        dynamo.BanditsDAOs.banditStateTableName -> Seq(dynamo.BanditsDAOs.banditKey),
        dynamo.BanditsDAOs.banditSettingsTableName -> Seq(
          dynamo.BanditsDAOs.banditKey
        ),
        dynamo.AdminDAOs.authTableName -> Seq(dynamo.AdminDAOs.authKey),
        dynamo.AdminDAOs.userTableName -> Seq(dynamo.AdminDAOs.userKey),
        dynamo.AdminDAOs.streamJobTableName -> Seq(dynamo.AdminDAOs.streamJobKey),
        dynamo.AnalysisDAOs.conversionKPITableName -> Seq(
          dynamo.AnalysisDAOs.conversionKPIKey
        )
      )

  lazy val dynamoDAOS: Resource[
    IO,
    (StateDAO[IO, Conversions], BanditSettingsDAO[IO, BanditSettings.Conversion])
  ] =
    localDynamoR
      .map { implicit client =>
        (
          dynamo.BanditsDAOs.banditState[IO, Conversions],
          dynamo.BanditsDAOs.banditSettings[IO, BanditSettings.Conversion]
        )
      }

  /**
    * An ConversionAPI resource that cleans up after
    */
  def apis(
      implicit logger: EventLogger[IO] = EventLogger.noop[IO],
      nowF: IO[Instant] = defaultNowF
    ): Resource[
    IO,
    (ConversionBMABAlg[IO], KPIModelApi[IO], AbtestAlg[IO])
  ] =
    (mangoDAOs, dynamoDAOS).tupled
      .flatMap {
        case (daos, (sd, ssd)) =>
          implicit val (abtestDAO, featureDAO, kpiDAO) = daos
          lazy val refreshPeriod = 0.seconds
          implicit val isd = sd
          implicit val issd = ssd

          AbtestAlg.defaultResource[IO](refreshPeriod).map { implicit abtestAlg =>
            implicit val ss = Sampler.default
            implicit val rng = RNG.default

            (
              implicitly,
              implicitly,
              abtestAlg
            )
          }
      }

}
