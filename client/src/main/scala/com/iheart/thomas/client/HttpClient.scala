/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package client

import java.time.OffsetDateTime

import cats.{Functor, Id, MonadError}
import cats.effect._
import com.iheart.thomas.model._
import lihua.Entity
import _root_.play.api.libs.json._
import cats.implicits._
import Formats._
import com.iheart.thomas.Error.NotFound
import com.iheart.thomas.analysis.KPIDistribution
import lihua.EntityId.toEntityId

import scala.concurrent.ExecutionContext
import scala.util.control.NoStackTrace
import com.iheart.thomas.client.Client.HttpServiceUrls
import org.http4s.Status
import org.http4s.client.UnexpectedStatus
import org.http4s.client.dsl.Http4sClientDsl

trait Client[F[_]] {
  def tests(asOf: Option[OffsetDateTime] = None): F[Vector[(Entity[Abtest], Feature)]]

  def test(feature: FeatureName, asOf: Option[OffsetDateTime] = None)
          (implicit F: Functor[F]): F[Option[Entity[Abtest]]] =
    tests(asOf).map(_.collectFirst {
      case (test, Feature(`feature`, _, _, _, _)) => test
    })

  def featureTests(feature: FeatureName): F[Vector[Entity[Abtest]]]

  def featureLatestTest(feature: FeatureName)(implicit F: MonadError[F, Throwable]): F[Entity[Abtest]] =
    featureTests(feature).flatMap(_.headOption.liftTo[F](NotFound(Some(s"No tests found under $feature"))))

  def getGroupMeta(tid: TestId): F[Map[GroupName, GroupMeta]]

  def addGroupMeta(tid: TestId, gm: JsObject, auto: Boolean): F[Entity[AbtestExtras]]

  def getKPI(name: String): F[KPIDistribution]

  def saveKPI(KPIDistribution: KPIDistribution): F[KPIDistribution]

}


import org.http4s.client.{Client => HClient}

class Http4sClient[F[_]: Sync](c: HClient[F], urls: HttpServiceUrls) extends PlayJsonHttp4sClient[F] with Client[F] {
  import org.http4s.{Method, Uri}
  import Method._


  def getKPI(name: String): F[KPIDistribution] =
    c.expect[KPIDistribution](urls.kPIs + "/name").adaptError{
      case UnexpectedStatus(status) if status == Status.NotFound => Error.NotFound(Some("kpi " + name + " is not found"))
    }

  def saveKPI(kd: KPIDistribution): F[KPIDistribution] =
    c.expect(POST(kd, Uri.unsafeFromString(urls.kPIs)))

  def tests(asOf: Option[OffsetDateTime] = None): F[Vector[(Entity[Abtest], Feature)]] = {
    val baseUrl: Uri = Uri.unsafeFromString(urls.tests)
    c.expect(asOf.fold(baseUrl) { ao =>
      baseUrl +? ("at" , (ao.toInstant.toEpochMilli / 1000).toString)
    })
  }

  def getGroupMeta(tid: TestId): F[Map[GroupName, GroupMeta]] =
    c.expect[Entity[AbtestExtras]](urls.groupMeta(tid)).map(_.data.groupMetas)

  def addGroupMeta(tid: TestId, gm: JsObject, auto: Boolean): F[Entity[AbtestExtras]] =
    c.expect(PUT(gm, Uri.unsafeFromString(urls.groupMeta(tid)) +? ("auto", auto)))

  def featureTests(feature: FeatureName): F[Vector[Entity[Abtest]]] =
    c.expect(urls.featureTests(feature))

}

object Http4sClient {
  import org.http4s.client.blaze.BlazeClientBuilder
  def resource[F[_]: ConcurrentEffect](urls: HttpServiceUrls,
                                       ec: ExecutionContext): Resource[F, Client[F]] = {
    BlazeClientBuilder[F](ec).resource.map(cl => new Http4sClient[F](cl, urls))
  }
}

object Client extends EntityReads {

  trait HttpServiceUrls {

    /**
     * Service URL corresponding to [[API]].getAllTestsCached
     */
    def tests: String

    /**
     * Service URL corresponding to [[analysis.KPIApi]].get
     */
    def kPIs: String

    def groupMeta(testId: TestId): String

    def featureTests(featureName: FeatureName): String
  }


  /**
   * service urls based on play example routes
   * @param root protocal + host + rootpath e.g. "http://localhost/internal"
   */
  class HttpServiceUrlsPlay(root: String) extends HttpServiceUrls {

    def tests: String = root + "/testsWithFeatures"

    def kPIs: String = root + "/kPIs"

    def groupMeta(testId: TestId) = root + "/tests/" + testId +  "/groups/metas"

    def featureTests(featureName: FeatureName): String = s"$root/features/$featureName/tests"
  }

  case class ErrorParseJson(errs: Seq[(JsPath, Seq[JsonValidationError])], body: JsValue) extends RuntimeException with NoStackTrace {
    override def getMessage: String = errs.toList.mkString(s"Error parsing json ($body):\n ", "; ", "")
  }

  /**
   * Shortcuts for getting the assigned group only.
   * @param serviceUrl for getting all running tests as of `time`
   */
  def assignGroups[F[_]: ConcurrentEffect](serviceUrl: String, time: Option[OffsetDateTime])(implicit ec: ExecutionContext): F[AssignGroups[Id]] =
    Http4sClient.resource[F](new HttpServiceUrlsPlay("mock") {
      override val tests: String = serviceUrl
    }, ec).use(_.tests(time).map(t => AssignGroups.fromTestsFeatures[Id](t)))
}

abstract class PlayJsonHttp4sClient[F[_]: Sync] extends EntityReads with Http4sClientDsl[F] {
  import org.http4s.play._
  import org.http4s.{EntityDecoder, EntityEncoder}

  implicit def jsonEncoderOf_[A: Writes]: EntityEncoder[F, A] = jsonEncoderOf[F, A]
  implicit def jsObjectEncoder: EntityEncoder[F, JsObject] = jsonEncoder[F].narrow
  implicit def jsonDeoder[A: Reads]: EntityDecoder[F, A] = jsonOf

}



trait EntityReads {

  implicit def entityFormat[T: Reads]: Reads[Entity[T]] = new Reads[Entity[T]] {
    def reads(json: JsValue): JsResult[Entity[T]] = for {
      id <- (json \ "_id" \ "$oid").validate[String]
      t <- json.validate[T]
    } yield Entity(id, t)
  }

}
