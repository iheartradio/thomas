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
import lihua.Entity
import play.api.libs.json._
import cats.implicits._
import Formats._
import com.iheart.thomas.Error.NotFound
import com.iheart.thomas.analysis.KPIDistribution
import lihua.EntityId.toEntityId
import play.api.libs.ws.{StandaloneWSRequest, StandaloneWSResponse}

import scala.concurrent.Future
import scala.util.control.NoStackTrace
import play.api.libs.ws.JsonBodyWritables._

trait Client[F[_]] {
  def tests(asOf: Option[OffsetDateTime] = None): F[Vector[(Entity[Abtest], Feature)]]
  def test(feature: FeatureName): F[Option[Entity[Abtest]]]

  def getKPI(name: String): F[KPIDistribution]

  def saveKPI(KPIDistribution: KPIDistribution): F[KPIDistribution]

  def close(): F[Unit]
}

object Client extends EntityReads {


  import akka.actor.ActorSystem
  import akka.stream.ActorMaterializer
  import play.api.libs.ws.ahc._

  import play.api.libs.ws.JsonBodyReadables._

  def http[F[_]](urls: HttpServiceUrls)(implicit F: Async[F]): F[Client[F]] = F.delay(new Client[F] {
    implicit val system = ActorSystem()

    implicit val materializer = ActorMaterializer()

    val ws = StandaloneAhcWSClient(AhcWSClientConfigFactory.forConfig())

    private def get[A: Reads](request: StandaloneWSRequest): F[A] =
      parse[A](request.addHttpHeaders("Accept" -> "application/json").get(), request.url)


    private def parse[A: Reads](resp: => Future[StandaloneWSResponse], url: String): F[A] = {
      IO.fromFuture(IO(resp)).to[F].flatMap { resp =>
        if(resp.status == 404) F.delay(println(url + " Not Found")) *> F.raiseError[A](NotFound(Some(url)))
        else
          resp.body[JsValue].validate[A].fold(
            errs => F.raiseError(ErrorParseJson(errs)),
            F.pure(_)
          )
      }
    }

    def tests(asOf: Option[OffsetDateTime] = None): F[Vector[(Entity[Abtest], Feature)]] = {
      val baseUrl = ws.url(urls.tests)

      get(asOf.fold(baseUrl){ ao =>
        baseUrl.addQueryStringParameters(("at", (ao.toInstant.toEpochMilli / 1000).toString))
      })
    }

    def test(feature: FeatureName): F[Option[Entity[Abtest]]] =
      get[Vector[Entity[Abtest]]](ws.url(urls.test(feature))).map(_.headOption)

    def getKPI(name: String): F[KPIDistribution] =
      get[KPIDistribution](ws.url(urls.kPIs + name))

    def saveKPI(kpi: KPIDistribution): F[KPIDistribution] =
      parse[KPIDistribution](ws.url(urls.kPIs).post(Json.toJson(kpi)), urls.kPIs)

    def close(): F[Unit] =
      F.delay(ws.close()) *> IO.fromFuture(IO(system.terminate())).to[F].void
  })


  trait HttpServiceUrls {
    //it takes an optional  `at` parameter for as of time
    def tests: String
    def test(featureName: FeatureName): String
    def kPIs: String
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
      val tests: String = serviceUrl
      def kPIs: String = ???
      def test(featureName: FeatureName): String = ???
    }).use(_.tests(time).map(t => AssignGroups.fromTestsFeatures[Id](t)))
}

trait EntityReads {

  implicit def entityFormat[T: Reads]: Reads[Entity[T]] = new Reads[Entity[T]] {
    def reads(json: JsValue): JsResult[Entity[T]] = for {
      id <- (json \ "_id" \ "$oid").validate[String]
      t <- json.validate[T]
    } yield Entity(id, t)
  }

}
