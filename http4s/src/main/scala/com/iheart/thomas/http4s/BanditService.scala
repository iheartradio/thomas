package com.iheart.thomas
package http4s

import cats.effect.{Async, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.implicits._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.bandit.bayesian.ConversionBMABAlg
import com.iheart.thomas.kafka.{
  BanditUpdater,
  ConversionBMABAlgResource,
  MessageProcessor
}
import mau.Repeating
import org.http4s.{EntityDecoder, HttpRoutes, Request, Response}
import org.http4s.dsl.Http4sDsl
import org.http4s.play._
import bandit.Formats._
import lihua.mongo.JsonFormats._
import com.iheart.thomas.analysis.{Conversions, KPIApi, KPIDistribution}
import com.iheart.thomas.bandit.BanditSpec
import com.iheart.thomas.bandit.`package`.ArmName
import com.iheart.thomas.bandit.tracking.EventLogger
import com.iheart.thomas.dynamo.ClientConfig
import com.iheart.thomas.kafka.BanditUpdater.KafkaConfig
import com.typesafe.config.Config
import play.api.libs.json._
import fs2.Stream
import org.http4s.server.Router

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import play.api.libs.json.Json.toJson

class BanditService[F[_]: Async] private (
    conversionsRunner: Repeating[F],
    apiAlg: ConversionBMABAlg[F],
    kpiAlg: KPIApi[F],
    banditUpdater: BanditUpdater[F])
    extends Http4sDsl[F] {
  private type PartialRoutes = PartialFunction[Request[F], F[Response[F]]]

  def backGroundProcess: Stream[F, Unit] = banditUpdater.consumer

  private implicit def decoder[A: Reads]: EntityDecoder[F, A] = jsonOf

  def routes =
    Router(
      "/bandits" -> HttpRoutes
        .of[F](managementRoutes orElse runnerRoutes orElse updaterRoutes),
      "/kpis" -> HttpRoutes
        .of[F](kpiRoutes)
    )

  private def runnerRoutes = {
    case PUT -> Root / "conversions" / "run" =>
      conversionsRunner.resume.ifA(
        Ok("all bandits resumed"),
        Conflict("bandits already running")
      )

    case GET -> Root / "conversions" / "run" =>
      conversionsRunner.running.ifA(
        Ok("all bandits are running"),
        Ok("all bandits are paused")
      )
    case DELETE -> Root / "conversions" / "run" =>
      conversionsRunner.pause.ifA(
        Ok("all bandits paused"),
        Conflict("bandits already paused")
      )
  }: PartialRoutes

  private def updaterRoutes = {
    case PUT -> Root / "conversions" / "updating" =>
      banditUpdater
        .pauseResume(true) *>
        Ok("conversions are being updated")

    case GET -> Root / "conversions" / "updating" =>
      banditUpdater.isPaused.flatMap(
        b => Ok(Json.prettyPrint(Json.obj("status" -> JsBoolean(!b))))
      )
    case DELETE -> Root / "conversions" / "updating" =>
      banditUpdater
        .pauseResume(false) *>
        Ok("conversions update are paused now")

  }: PartialRoutes

  private def kpiRoutes = {
    case GET -> Root =>
      kpiAlg.getAll

    case GET -> Root / kpiName =>
      kpiAlg.get(kpiName)

    case req @ POST -> Root =>
      req.as[KPIDistribution].flatMap(kpiAlg.upsert _)

  }: PartialRoutes

  private implicit def toResponse[A: Writes](fa: F[A]): F[Response[F]] =
    fa.flatMap(a => Ok(toJson(a)))

  private def managementRoutes = {
    case req @ PUT -> Root / "conversions" / "features" / feature / "reward_state" =>
      req.as[Map[ArmName, Conversions]].flatMap { rwst =>
        apiAlg.updateRewardState(feature, rwst)
      }

    case PUT -> Root / "conversions" / "features" / feature / "reallocate" =>
      apiAlg.reallocate(feature)

    case GET -> Root / "conversions" / "features" / feature =>
      apiAlg.currentState(feature)

    case GET -> Root / "conversions" / "features" =>
      apiAlg.getAll

    case req @ POST -> Root / "conversions" / "features" =>
      req.as[BanditSpec].flatMap { bs =>
        apiAlg.init(bs)
      }

    case GET -> Root / "conversions" / "running" =>
      apiAlg.runningBandits(None)

  }: PartialRoutes

}

object BanditService {

  case class BanditRunnerConfig(repeat: FiniteDuration)
  case class BanditServiceConfig(
      kafka: KafkaConfig,
      dynamo: ClientConfig,
      runner: BanditRunnerConfig)

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
      F[_]: ConcurrentEffect: Timer: ContextShift: MessageProcessor: EventLogger
    ](configResource: Option[String] = None
    )(implicit ex: ExecutionContext
    ): Resource[F, BanditService[F]] = {
    Resource
      .liftF(BanditServiceConfig.load(configResource))
      .flatMap {
        case (bsc, root) =>
          create[F](bsc.kafka, root, bsc.dynamo, bsc.runner.repeat)
      }

  }

  def create[
      F[_]: ConcurrentEffect: Timer: ContextShift: MessageProcessor: EventLogger
    ](kafkaConfig: KafkaConfig,
      mongoConfig: Config,
      dynamoConfig: dynamo.ClientConfig,
      reallocationRepeatDuration: FiniteDuration
    )(implicit ex: ExecutionContext
    ): Resource[F, BanditService[F]] =
    dynamo.client[F](dynamoConfig).flatMap { implicit dc =>
      mongo.daosResource(mongoConfig).flatMap { implicit daos =>
        create[F](kafkaConfig, reallocationRepeatDuration)
      }
    }

  def create[
      F[_]: ConcurrentEffect: Timer: ContextShift: mongo.DAOs: MessageProcessor: EventLogger
    ](kafkaConfig: KafkaConfig,
      reallocationRepeatDuration: FiniteDuration
    )(implicit ex: ExecutionContext,
      amazonClient: AmazonDynamoDBAsync
    ): Resource[F, BanditService[F]] = {
    import mongo.extracKPIDistDAO
    ConversionBMABAlgResource[F].flatMap { implicit conversionBMAB =>
      mau.Repeating
        .resource[F](
          conversionBMAB.reallocateAllRunning.void,
          reallocationRepeatDuration,
          true
        )
        .evalMap { repeating =>
          BanditUpdater.create[F](kafkaConfig).map { bu =>
            new BanditService[F](repeating, conversionBMAB, KPIApi.default[F], bu)
          }
        }
    }
  }

}
