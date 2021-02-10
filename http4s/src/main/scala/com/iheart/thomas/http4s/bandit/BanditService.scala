package com.iheart.thomas
package http4s
package bandit

import cats.effect.{Async, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.implicits._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import com.iheart.thomas.bandit.bayesian.{
  BanditSettings,
  ConversionBMABAlg,
  ConversionBanditSpec
}
import com.iheart.thomas.kafka.{
  BanditUpdater,
  ConversionBMABAlgResource,
  MessageProcessor
}
import org.http4s.{EntityDecoder, HttpRoutes, Request, Response}
import org.http4s.dsl.Http4sDsl
import org.http4s.play._
import com.iheart.thomas.bandit.Formats._
import lihua.mongo.JsonFormats._
import com.iheart.thomas.analysis.{KPIModel, KPIModelApi}
import com.iheart.thomas.bandit.tracking.EventLogger
import com.iheart.thomas.dynamo.ClientConfig
import com.typesafe.config.Config
import _root_.play.api.libs.json._
import fs2.Stream
import org.http4s.server.Router

import scala.concurrent.ExecutionContext
import _root_.play.api.libs.json.Json.toJson
import cats.NonEmptyParallel

class BanditService[F[_]: Async: Timer] private (
    apiAlg: ConversionBMABAlg[F],
    kpiDistApi: KPIModelApi[F],
    banditUpdater: BanditUpdater[F]
  )(implicit log: EventLogger[F])
    extends Http4sDsl[F] {
  private type PartialRoutes = PartialFunction[Request[F], F[Response[F]]]

  def backGroundProcess: Stream[F, Unit] = banditUpdater.consumer

  private implicit def decoder[A: Reads]: EntityDecoder[F, A] = jsonOf

  def routes =
    Router(
      "/bandits" -> HttpRoutes
        .of[F](managementRoutes orElse updaterRoutes),
      "/kpiModels" -> HttpRoutes
        .of[F](kpiModelsRoutes)
    )

  private def updaterRoutes = {
    case PUT -> Root / "conversions" / "updating" =>
      banditUpdater
        .pauseResume(false) *>
        Ok("conversions are being updated")

    case GET -> Root / "conversions" / "updating" =>
      banditUpdater.isPaused.flatMap(b =>
        Ok(Json.prettyPrint(Json.obj("updating" -> JsBoolean(!b))))
      )
    case DELETE -> Root / "conversions" / "updating" =>
      banditUpdater
        .pauseResume(true) *>
        Ok("conversions update are paused now")

  }: PartialRoutes

  private def kpiModelsRoutes = {
    case GET -> Root =>
      kpiDistApi.getAll

    case GET -> Root / kpiName =>
      kpiDistApi.get(kpiName)

    case req @ POST -> Root =>
      req.as[KPIModel].flatMap(kpiDistApi.upsert _)

  }: PartialRoutes

  private implicit def toResponse[A: Writes](fa: F[A]): F[Response[F]] =
    fa.flatMap(a => Ok(toJson(a)))

  private def managementRoutes = {
    case req @ PUT -> Root / "conversions" / "features" / feature / "settings" =>
      req.as[BanditSettings[BanditSettings.Conversion]].flatMap { s =>
        apiAlg.update(s)
      }

    case PUT -> Root / "conversions" / "features" / feature / "reallocate" =>
      apiAlg.updatePolicy(feature)

    case GET -> Root / "conversions" / "features" / feature =>
      apiAlg.currentState(feature)

    case GET -> Root / "conversions" / "features" =>
      apiAlg.getAll

    case DELETE -> Root / "conversions" / "features" / feature =>
      apiAlg.delete(feature) *> Ok(s"$feature, if existed, was removed.")

    case req @ POST -> Root / "conversions" / "features" =>
      req.as[ConversionBanditSpec].flatMap { bs =>
        apiAlg.init(bs)
      }

    case GET -> Root / "conversions" / "running" =>
      apiAlg.runningBandits(None)

  }: PartialRoutes

}

object BanditService {

  case class BanditServiceConfig(
      updater: BanditUpdater.Config,
      dynamo: ClientConfig)

  object BanditServiceConfig {

    def load[F[_]: Sync](configResource: String): F[(BanditServiceConfig, Config)] =
      load(Some(configResource))

    def load[F[_]: Sync](
        configResource: Option[String] = None
      ): F[(BanditServiceConfig, Config)] = {
      import pureconfig._
      import pureconfig.generic.auto._
      import pureconfig.module.catseffect._
      val configSource =
        configResource.fold(ConfigSource.default)(ConfigSource.resources)
      (
        configSource.at("thomas.bandits").loadF[F, BanditServiceConfig],
        configSource.loadF[F, Config]
      ).tupled
    }

  }

  def create[
      F[_]: ConcurrentEffect: Timer: ContextShift: MessageProcessor: EventLogger: NonEmptyParallel
    ](configResource: Option[String] = None
    )(implicit ex: ExecutionContext
    ): Resource[F, BanditService[F]] = {
    Resource
      .liftF(BanditServiceConfig.load(configResource))
      .flatMap {
        case (bsc, root) =>
          create[F](bsc.updater, root, bsc.dynamo)
      }

  }

  def create[
      F[_]: ConcurrentEffect: Timer: ContextShift: MessageProcessor: EventLogger: NonEmptyParallel
    ](buConfig: BanditUpdater.Config,
      mongoConfig: Config,
      dynamoConfig: dynamo.ClientConfig
    )(implicit ex: ExecutionContext
    ): Resource[F, BanditService[F]] =
    dynamo.client[F](dynamoConfig).flatMap { implicit dc =>
      mongo.daosResource(mongoConfig).flatMap { implicit daos =>
        create[F](buConfig)
      }
    }

  def create[
      F[_]: ConcurrentEffect: Timer: ContextShift: mongo.DAOs: MessageProcessor: EventLogger: NonEmptyParallel
    ](buConfig: BanditUpdater.Config
    )(implicit ex: ExecutionContext,
      amazonClient: DynamoDbAsyncClient
    ): Resource[F, BanditService[F]] = {
    import mongo.extracKPIDistDAO
    ConversionBMABAlgResource[F].evalMap { implicit conversionBMAB =>
      BanditUpdater.create[F](buConfig).map { bu =>
        new BanditService[F](
          conversionBMAB,
          KPIModelApi.default[F],
          bu
        )
      }
    }
  }

}
