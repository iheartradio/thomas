package com.iheart.thomas
package http4s

import cats.effect.{Async, Concurrent, Resource, Timer}
import cats.implicits._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.kafka.ConversionBMABAlgResource
import com.typesafe.config.Config
import mau.Repeating
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class BanditService[F[_]: Async] private (conversionsRunner: Repeating[F])
    extends Http4sDsl[F] {

  def routes =
    Router("internal/bandits" -> HttpRoutes.of[F] {
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
    })
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
    ): Resource[F, BanditService[F]] =
    reallocateAllConversions[F](mongoConfig, reallocationRepeatDuration).map {
      repeating =>
        new BanditService[F](repeating)
    }

  def reallocateAllConversions[F[_]: Timer: Concurrent](
      mongoConfig: Config,
      reallocationRepeatDuration: FiniteDuration
    )(implicit ex: ExecutionContext,
      amazonClient: AmazonDynamoDBAsync
    ): Resource[F, Repeating[F]] = {
    ConversionBMABAlgResource[F](mongoConfig).flatMap { conversionBMAB =>
      mau.Repeating.resource[F](
        conversionBMAB.reallocateAllRunning.void,
        reallocationRepeatDuration,
        true
      )
    }
  }

}
