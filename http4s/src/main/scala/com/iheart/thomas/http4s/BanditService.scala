package com.iheart.thomas
package http4s

import cats.effect.{Async, ConcurrentEffect, ContextShift, Resource, Timer}
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
import com.iheart.thomas.analysis.Conversions
import com.iheart.thomas.bandit.BanditSpec
import com.iheart.thomas.bandit.`package`.ArmName
import com.iheart.thomas.kafka.BanditUpdater.KafkaConfig
import play.api.libs.json._
import fs2.Stream
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import play.api.libs.json.Json.toJson

class BanditService[F[_]: Async] private (
    conversionsRunner: Repeating[F],
    apiAlg: ConversionBMABAlg[F],
    banditUpdater: BanditUpdater[F])
    extends Http4sDsl[F] {
  private type PartialRoutes = PartialFunction[Request[F], F[Response[F]]]

  def backGroundProcess: Stream[F, Unit] = banditUpdater.consumer

  private implicit def decoder[A: Reads]: EntityDecoder[F, A] = jsonOf

  def routes =
    HttpRoutes.of[F](managementRoutes orElse runnerRoutes orElse updaterRoutes)

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

    case req @ POST -> Root / "conversions" / "features" =>
      req.as[BanditSpec].flatMap { bs =>
        apiAlg.init(bs)
      }

    case GET -> Root / "conversions" / "features" / "running" =>
      apiAlg.runningBandits(None)

  }: PartialRoutes

}

object BanditService {
//  def create[F[_]: Concurrent: Timer](
//      mongoConfig: Config,
//      dynamoConfig: dynamo.ClientConfig,
//      reallocationRepeatDuration: FiniteDuration
//    )(implicit ex: ExecutionContext
//    ): Resource[F, BanditService[F]] =
//    dynamo.client[F](dynamoConfig).flatMap { implicit dc =>
//      mongo.daosResource(mongoConfig).flatMap { implicit daos =>
//        create[F](reallocationRepeatDuration)
//      }
//    }

  def create[
      F[_]: ConcurrentEffect: Timer: ContextShift: mongo.DAOs: MessageProcessor
    ](kafkaConfig: KafkaConfig,
      reallocationRepeatDuration: FiniteDuration
    )(implicit ex: ExecutionContext,
      amazonClient: AmazonDynamoDBAsync
    ): Resource[F, BanditService[F]] = {

    ConversionBMABAlgResource[F].flatMap { implicit conversionBMAB =>
      mau.Repeating
        .resource[F](
          conversionBMAB.reallocateAllRunning.void,
          reallocationRepeatDuration,
          true
        )
        .evalMap { repeating =>
          BanditUpdater.create[F](kafkaConfig).map { bu =>
            new BanditService[F](repeating, conversionBMAB, bu)
          }
        }
    }
  }

}
