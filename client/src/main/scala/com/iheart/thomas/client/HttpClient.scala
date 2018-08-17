/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package client

import java.time.OffsetDateTime

import cats.Id
import cats.effect.{Async, IO}
import com.iheart.thomas.model.{Abtest, Feature}
import lihua.mongo.Entity
import play.api.libs.json.{JsPath, JsValue, JsonValidationError}
import cats.implicits._
import com.iheart.thomas.persistence.Formats._
import cats.effect.implicits._
import scala.util.control.NoStackTrace

trait Client[F[_]] {
  def tests(asOf: Option[OffsetDateTime] = None): F[Vector[(Entity[Abtest], Feature)]]
  def close(): F[Unit]
}

object Client {

  import akka.actor.ActorSystem
  import akka.stream.ActorMaterializer
  import play.api.libs.ws.ahc._

  import play.api.libs.ws.JsonBodyReadables._

  def http[F[_]](serviceUrl: String)(implicit F: Async[F]): F[Client[F]] = F.delay(new Client[F] {
    implicit val system = ActorSystem()

    implicit val materializer = ActorMaterializer()

    val ws = StandaloneAhcWSClient(AhcWSClientConfigFactory.forConfig())

    def tests(asOf: Option[OffsetDateTime] = None): F[Vector[(Entity[Abtest], Feature)]] = {
      val request = ws.url(serviceUrl)

      IO.fromFuture(IO(request.addHttpHeaders("Accept" -> "application/json").get())).to[F].flatMap { resp =>
        resp.body[JsValue].validate[Vector[(Entity[Abtest], Feature)]].fold(
          errs => F.raiseError(ErrorParseJson(errs)),
          F.pure(_)
        )
      }
    }

    def close(): F[Unit] =
      F.delay(ws.close()) *> IO.fromFuture(IO(system.terminate())).to[F].void
  })

  case class ErrorParseJson(errs: Seq[(JsPath, Seq[JsonValidationError])]) extends RuntimeException with NoStackTrace


  def assignGroups[F[_]: Async](serviceUrl: String, time: Option[OffsetDateTime]): F[AssignGroups[Id]] =
    Client.http[F](serviceUrl).bracket(
      _.tests(time).map(t => AssignGroups.fromTestsFeatures[Id](t))
    )(_.close())
}

