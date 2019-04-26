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
import _root_.play.api.libs.json._
import cats.implicits._
import Formats._
import com.iheart.thomas.Error.NotFound
import com.iheart.thomas.analysis.KPIDistribution
import lihua.EntityId.toEntityId
import _root_.play.api.libs.ws.{StandaloneWSRequest, StandaloneWSResponse}

import scala.concurrent.Future
import scala.util.control.NoStackTrace
import _root_.play.api.libs.ws.JsonBodyWritables._

trait Client[F[_]] {
  def tests(asOf: Option[OffsetDateTime] = None): F[Vector[(Entity[Abtest], Feature)]]

  def test(feature: FeatureName, asOf: Option[OffsetDateTime] = None): F[Option[Entity[Abtest]]]

  def getKPI(name: String): F[KPIDistribution]

  def saveKPI(KPIDistribution: KPIDistribution): F[KPIDistribution]

  def close(): F[Unit]
}

object Client extends EntityReads {


  import akka.actor.ActorSystem
  import akka.stream.ActorMaterializer
  import _root_.play.api.libs.ws.ahc._

  import _root_.play.api.libs.ws.JsonBodyReadables._

  class PlayClient[F[_]](implicit F: Async[F]) extends EntityReads {

    implicit private val system = ActorSystem()

    implicit private val materializer = ActorMaterializer()

    val ws = StandaloneAhcWSClient(AhcWSClientConfigFactory.forConfig())

    def get[A: Reads](request: StandaloneWSRequest): F[A] =
      parse[A](request.addHttpHeaders("Accept" -> "application/json").get(), request.url)


    def parse[A: Reads](resp: => Future[StandaloneWSResponse], url: String): F[A] = {
      IO.fromFuture(IO(resp)).to[F].flatMap { resp =>
        if (resp.status == 404) F.raiseError[A](NotFound(Some(url)))
        else {
          val jsBody = resp.body[JsValue]
          jsBody.validate[A].fold(
            errs => F.raiseError(ErrorParseJson(errs, jsBody)),
            F.pure(_)
          )
        }
      }
    }

    def jsonPost[Req: Writes, Res: Reads](req: Req, url: String): F[Res] =
      parse[Res](ws.url(url).post(Json.toJson(req)), url)

    def close(): F[Unit] =
      F.delay(ws.close()) *> IO.fromFuture(IO(system.terminate())).to[F].void
  }

  /**
   * lower level API, It's recommended to use Client.create instead
   * @return
   */
  def httpPlay[F[_]](urls: HttpServiceUrls)(implicit F: Async[F]): F[PlayClient[F] with Client[F]] = F.delay(new PlayClient[F] with Client[F] {
    def tests(asOf: Option[OffsetDateTime] = None): F[Vector[(Entity[Abtest], Feature)]] = {
      val baseUrl = ws.url(urls.tests)

      get(asOf.fold(baseUrl) { ao =>
        baseUrl.addQueryStringParameters(("at", (ao.toInstant.toEpochMilli / 1000).toString))
      })
    }

    def test(feature: FeatureName, asOf: Option[OffsetDateTime] = None): F[Option[Entity[Abtest]]] =
      tests(asOf).map(_.collectFirst {
        case (test, Feature(`feature`, _, _, _, _)) => test
      })

    def getKPI(name: String): F[KPIDistribution] =
      get[KPIDistribution](ws.url(urls.kPIs + name))

    def saveKPI(kpi: KPIDistribution): F[KPIDistribution] = jsonPost(kpi, urls.kPIs)
  })

  trait HttpServiceUrls {

    /**
     * Service URL corresponding to [[API]].getAllTestsCached
     */
    def tests: String

    /**
     * Service URL corresponding to [[analysis.KPIApi]].get
     */
    def kPIs: String
  }

  case class HttpServiceUrlsSimple(tests: String, kPIs: String) extends HttpServiceUrls

  def create[F[_]: Async](serviceUrl: HttpServiceUrls): Resource[F, Client[F]] =
    Resource.make(httpPlay(serviceUrl))(_.close()).widen

  case class ErrorParseJson(errs: Seq[(JsPath, Seq[JsonValidationError])], body: JsValue) extends RuntimeException with NoStackTrace {
    override def getMessage: String = errs.toList.mkString(s"Error parsing json ($body):\n ", "; ", "")
  }

  /**
   * Shortcuts for getting the assigned group only.
   * @param serviceUrl for getting all running tests as of `time`
   */
  def assignGroups[F[_]: Async](serviceUrl: String, time: Option[OffsetDateTime]): F[AssignGroups[Id]] =
    Client.create[F](new HttpServiceUrls {
      val tests: String = serviceUrl
      def kPIs: String = ???
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
