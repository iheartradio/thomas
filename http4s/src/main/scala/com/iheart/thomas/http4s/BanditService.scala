package com.iheart.thomas
package http4s

import cats.effect.{Async, Concurrent, Resource, Timer}
import cats.implicits._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.bandit.bayesian.ConversionBMABAlg
import com.iheart.thomas.kafka.ConversionBMABAlgResource
import com.typesafe.config.Config
import mau.Repeating
import org.http4s.{EntityDecoder, HttpRoutes, Request, Response}
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.play._
import bandit.Formats._
import lihua.mongo.JsonFormats._
import com.iheart.thomas.analysis.Conversions
import com.iheart.thomas.bandit.BanditSpec
import com.iheart.thomas.bandit.`package`.ArmName
import play.api.libs.json._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import play.api.libs.json.Json.toJson

class BanditService[F[_]: Async] private (
    conversionsRunner: Repeating[F],
    apiAlg: ConversionBMABAlg[F])
    extends Http4sDsl[F] {
  type PartialRoutes = PartialFunction[Request[F], F[Response[F]]]

  implicit def decoder[A: Reads]: EntityDecoder[F, A] = jsonOf

  def routes =
    Router(
      "internal/bandits" -> HttpRoutes.of[F](managementRoutes orElse runnerRoutes)
    )

  def runnerRoutes = {
    case PUT -> Root / "conversions" / "run" =>
      conversionsRunner.resume.ifA(
        Ok("all bandits resumed"),
        Conflict("bandits already running")
      )
    case DELETE -> Root / "conversions" / "run" =>
      conversionsRunner.pause.ifA(
        Ok("all bandits paused"),
        Conflict("bandits already paused")
      )
  }: PartialRoutes

  implicit def toResponse[A: Writes](fa: F[A]): F[Response[F]] =
    fa.flatMap(a => Ok(toJson(a)))

  def managementRoutes = {
    case req @ PUT -> Root / "conversions" / "features" / feature / "reward_state" =>
      req.as[Map[ArmName, Conversions]].flatMap { rwst =>
        apiAlg.updateRewardState(feature, rwst)
      }

    case PUT -> Root / "conversions" / "features" / feature / "reallocate" =>
      apiAlg.reallocate(feature)

    case GET -> Root / "conversions" / "features" / feature =>
      apiAlg.currentState(feature)

    case req @ POST -> Root / "conversions" / "features" =>
      req.as[BanditSpec].flatMap { bs =>
        apiAlg.init(bs)
      }

    case GET -> Root / "conversions" / "features" / "running" =>
      apiAlg.runningBandits(None)

  }: PartialRoutes

}

object BanditService {
  def create[F[_]: Concurrent: Timer](
      mongoConfig: Config,
      dynamoConfig: dynamo.ClientConfig,
      reallocationRepeatDuration: FiniteDuration
    )(implicit ex: ExecutionContext
    ): Resource[F, BanditService[F]] =
    dynamo.client[F](dynamoConfig).flatMap { implicit dc =>
      create[F](mongoConfig, reallocationRepeatDuration)
    }

  def create[F[_]: Concurrent: Timer](
      mongoConfig: Config,
      reallocationRepeatDuration: FiniteDuration
    )(implicit ex: ExecutionContext,
      amazonClient: AmazonDynamoDBAsync
    ): Resource[F, BanditService[F]] = {

    ConversionBMABAlgResource[F](mongoConfig).flatMap { conversionBMAB =>
      mau.Repeating
        .resource[F](
          conversionBMAB.reallocateAllRunning.void,
          reallocationRepeatDuration,
          true
        )
        .map { repeating =>
          new BanditService[F](repeating, conversionBMAB)
        }
    }
  }

}
