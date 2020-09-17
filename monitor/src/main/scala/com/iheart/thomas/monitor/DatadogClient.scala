package com.iheart.thomas.monitor

import cats.effect.{ConcurrentEffect, Resource, Sync}
import org.http4s.client.{Client => Http4sClient}
import org.http4s.play._
import org.http4s.client.dsl.Http4sClientDsl
import _root_.play.api.libs.json.{JsObject, Json}
import cats.implicits._
import com.typesafe.config.Config
import org.http4s.client.blaze.BlazeClientBuilder
import pureconfig.ConfigSource
import pureconfig.module.catseffect.CatsEffectConfigSource
import DatadogClient.ErrorResponseFromDataDogService

import scala.concurrent.ExecutionContext
import scala.util.control.NoStackTrace

class DatadogClient[F[_]](
    c: Http4sClient[F],
    apiKey: String
  )(implicit
    F: ConcurrentEffect[F])
    extends Http4sClientDsl[F] {
  import org.http4s.{Method, Uri, EntityEncoder}
  import Method._

  implicit def jsObjectEncoder: EntityEncoder[F, JsObject] =
    jsonEncoder[F].narrow

  def send(e: Event)(errorHandler: Throwable => F[Unit]): F[Unit] = {
    F.start(
        c.successful(
            POST(
              Json.toJson(e),
              Uri
                .unsafeFromString(
                  "https://api.datadoghq.com/api/v1/events"
                )
                .withQueryParam("api_key", apiKey)
            )
          )
          .ensure(ErrorResponseFromDataDogService)(identity)
          .void
          .handleErrorWith(errorHandler)
      )
      .void
  }
}

object DatadogClient {

  case object ErrorResponseFromDataDogService
      extends RuntimeException
      with NoStackTrace

  def resource[F[_]: ConcurrentEffect](
      ec: ExecutionContext,
      apiKey: String
    ): Resource[F, DatadogClient[F]] =
    BlazeClientBuilder[F](ec).resource
      .map(new DatadogClient[F](_, apiKey))

  def fromConfig[F[_]: ConcurrentEffect: Sync](
      ec: ExecutionContext,
      cfg: Config
    ): Resource[F, DatadogClient[F]] =
    Resource
      .liftF(
        ConfigSource
          .fromConfig(cfg)
          .at("thomas.datadog.api-key")
          .loadF[F, String]
      )
      .flatMap(resource(ec, _))
}
