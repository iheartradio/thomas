package com.iheart.thomas
package testkit

import java.time.Instant
import cats.effect.{IO, Resource}
import cats.implicits._
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.bandit.bayesian.ConversionBMABAlg
import com.iheart.thomas.{dynamo, mongo}
import com.stripe.rainier.sampler.{RNG, SamplerConfig}
import com.typesafe.config.ConfigFactory
import _root_.play.api.libs.json.Json
import com.iheart.thomas.analysis.{ConversionKPI, KPIRepo}
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import com.iheart.thomas.http4s.AuthImp
import com.iheart.thomas.http4s.auth.AuthenticationAlg
import com.iheart.thomas.tracking.EventLogger
import dynamo.DynamoFormats._

import scala.concurrent.duration._
import dynamo.AnalysisDAOs._
import dynamo.BanditsDAOs._

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
                case (abtestDAO, featureDAO) =>
                  List(abtestDAO, featureDAO)
                    .traverse(_.removeAll(Json.obj()))
                    .void
              }
        }
    }

  val tables =
    dynamo.BanditsDAOs.tables ++ dynamo.AdminDAOs.tables ++ dynamo.AnalysisDAOs.tables

  lazy val localDynamoR =
    LocalDynamo
      .clientWithTables[IO](
        tables.map(_.map(Seq(_))): _*
      )

  /**
    * An ConversionAPI resource that cleans up after
    */
  def apis(
      implicit logger: EventLogger[IO] = EventLogger.noop[IO],
      nowF: IO[Instant] = defaultNowF
    ): Resource[
    IO,
    (ConversionBMABAlg[IO], AbtestAlg[IO], KPIRepo[IO, ConversionKPI])
  ] =
    (mangoDAOs, localDynamoR).tupled
      .flatMap { deps =>
        implicit val ((abtestDAO, featureDAO), dynamoDb) = deps
        lazy val refreshPeriod = 0.seconds
        AbtestAlg.defaultResource[IO](refreshPeriod).map { implicit abtestAlg =>
          implicit val ss = SamplerConfig.default
          implicit val rng = RNG.default

          (
            implicitly,
            abtestAlg,
            implicitly
          )
        }
      }

  def authAlg(
      implicit dc: DynamoDbAsyncClient
    ): Resource[IO, AuthenticationAlg[IO, AuthImp]] =
    Resource.liftF(AuthenticationAlg.default[IO](sys.env("THOMAS_ADMIN_KEY")))

  def abtestAlg(
      implicit logger: EventLogger[IO] = EventLogger.noop[IO],
      nowF: IO[Instant] = defaultNowF
    ): Resource[
    IO,
    AbtestAlg[IO]
  ] =
    mangoDAOs.flatMap { daos =>
      implicit val (abtestDAO, featureDAO) = daos
      lazy val refreshPeriod = 0.seconds
      AbtestAlg.defaultResource[IO](refreshPeriod)
    }

}
