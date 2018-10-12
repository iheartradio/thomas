/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package client

import java.time.OffsetDateTime

import cats.Id
import cats.effect.{Async, IO, Resource}
import com.iheart.thomas.model.{Abtest, Feature, FeatureName}
import lihua.mongo.Entity
import play.api.libs.json.{JsPath, JsValue, JsonValidationError, Reads}
import cats.implicits._
import com.iheart.thomas.persistence.Formats._
import play.api.libs.ws.StandaloneWSRequest

import scala.util.control.NoStackTrace

trait Client[F[_]] {
  def tests(asOf: Option[OffsetDateTime] = None): F[Vector[(Entity[Abtest], Feature)]]
  def test(feature: FeatureName): F[Option[Entity[Abtest]]]
  def close(): F[Unit]
}

object Client {

  import akka.actor.ActorSystem
  import akka.stream.ActorMaterializer
  import play.api.libs.ws.ahc._

  import play.api.libs.ws.JsonBodyReadables._

  def http[F[_]](urls: HttpServiceUrls)(implicit F: Async[F]): F[Client[F]] = F.delay(new Client[F] {
    implicit val system = ActorSystem()

    implicit val materializer = ActorMaterializer()

    val ws = StandaloneAhcWSClient(AhcWSClientConfigFactory.forConfig())

    private def req[A: Reads](request: StandaloneWSRequest): F[A] = {
      IO.fromFuture(IO(request.addHttpHeaders("Accept" -> "application/json").get())).to[F].flatMap { resp =>
        resp.body[JsValue].validate[A].fold(
          errs => F.raiseError(ErrorParseJson(errs)),
          F.pure(_)
        )
      }
    }

    def tests(asOf: Option[OffsetDateTime] = None): F[Vector[(Entity[Abtest], Feature)]] = {
      val baseUrl = ws.url(urls.tests)

      req(asOf.fold(baseUrl){ ao =>
        baseUrl.addQueryStringParameters(("at", (ao.toInstant.toEpochMilli / 1000).toString))
      })
    }

    def test(feature: FeatureName): F[Option[Entity[Abtest]]] =
      req[Vector[Entity[Abtest]]](ws.url(urls.test(feature))).map(_.headOption)

    def close(): F[Unit] =
      F.delay(ws.close()) *> IO.fromFuture(IO(system.terminate())).to[F].void
  })


  trait HttpServiceUrls {
    //it takes an optional  `at` parameter for as of time
    def tests: String
    def test(featureName: FeatureName): String
  }

  def create[F[_]: Async](serviceUrl: HttpServiceUrls): Resource[F, Client[F]] =
    Resource.make(http(serviceUrl))(_.close())

  case class ErrorParseJson(errs: Seq[(JsPath, Seq[JsonValidationError])]) extends RuntimeException with NoStackTrace

  /**
   * Shortcuts for getting the assigned group only.
   * @param serviceUrl for getting all running tests as of `time`
   */
  def assignGroups[F[_]: Async](serviceUrl: String, time: Option[OffsetDateTime]): F[AssignGroups[Id]] =
    Client.create[F](new HttpServiceUrls {
      def tests: String = serviceUrl
      def test(featureName: FeatureName): String = ???
    }).use(_.tests(time).map(t => AssignGroups.fromTestsFeatures[Id](t)))
}

