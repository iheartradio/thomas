/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package client

import java.time.OffsetDateTime

import cats.{Functor, MonadError}
import cats.effect._
import com.iheart.thomas.abtest.model._
import lihua.Entity
import _root_.play.api.libs.json._
import cats.implicits._
import com.iheart.thomas.abtest.Error
import com.iheart.thomas.analysis.KPIDistribution
import abtest.Formats._
import scala.concurrent.ExecutionContext
import scala.util.control.NoStackTrace
import com.iheart.thomas.client.AbtestClient.HttpServiceUrls
import org.http4s.Status
import org.http4s.client.UnexpectedStatus

trait AbtestClient[F[_]] {
  def tests(asOf: Option[OffsetDateTime] = None): F[Vector[(Entity[Abtest], Feature)]]

  def test(feature: FeatureName, asOf: Option[OffsetDateTime] = None)(
      implicit F: Functor[F]): F[Option[Entity[Abtest]]] =
    tests(asOf).map(_.collectFirst {
      case (test, Feature(`feature`, _, _, _, _)) => test
    })

  def featureTests(feature: FeatureName): F[Vector[Entity[Abtest]]]

  def featureLatestTest(feature: FeatureName)(
      implicit F: MonadError[F, Throwable]): F[Entity[Abtest]] =
    featureTests(feature).flatMap(
      _.headOption.liftTo[F](Error.NotFound(s"No tests found under $feature")))

  def getGroupMeta(tid: TestId): F[Map[GroupName, GroupMeta]]

  def addGroupMeta(tid: TestId, gm: JsObject, auto: Boolean): F[Entity[Abtest]]

  def removeGroupMetas(tid: TestId, auto: Boolean): F[Entity[Abtest]]

  def getKPI(name: String): F[KPIDistribution]

  def saveKPI(KPIDistribution: KPIDistribution): F[KPIDistribution]

}

import org.http4s.client.{Client => HClient}

class Http4SAbtestClient[F[_]: Sync](c: HClient[F], urls: HttpServiceUrls)
    extends PlayJsonHttp4sClient[F]
    with AbtestClient[F] {
  import org.http4s.{Method, Uri}
  import Method._

  def getKPI(name: String): F[KPIDistribution] =
    c.expect[KPIDistribution](urls.kPIs + "/" + name).adaptError {
      case UnexpectedStatus(status) if status == Status.NotFound =>
        Error.NotFound("KPI " + name + " is not found")
    }

  def saveKPI(kd: KPIDistribution): F[KPIDistribution] =
    c.expect(POST(kd, Uri.unsafeFromString(urls.kPIs)))

  def tests(asOf: Option[OffsetDateTime] = None): F[Vector[(Entity[Abtest], Feature)]] = {
    val baseUrl: Uri = Uri.unsafeFromString(urls.tests)
    c.expect(asOf.fold(baseUrl) { ao =>
      baseUrl +? ("at", (ao.toInstant.toEpochMilli / 1000).toString)
    })
  }

  def getGroupMeta(tid: TestId): F[Map[GroupName, GroupMeta]] =
    for {
      req <- GET(Uri.unsafeFromString(urls.test(tid)))
      test <- c.expect[Entity[Abtest]](req)
    } yield test.data.groupMetas

  def addGroupMeta(tid: TestId, gm: JsObject, auto: Boolean): F[Entity[Abtest]] =
    c.expect(PUT(gm, Uri.unsafeFromString(urls.groupMeta(tid)) +? ("auto", auto)))

  def removeGroupMetas(tid: TestId, auto: Boolean): F[Entity[Abtest]] =
    c.expect(DELETE(Uri.unsafeFromString(urls.groupMeta(tid)) +? ("auto", auto)))

  def featureTests(feature: FeatureName): F[Vector[Entity[Abtest]]] =
    c.expect(urls.featureTests(feature))

}

object Http4SAbtestClient {
  import org.http4s.client.blaze.BlazeClientBuilder
  def resource[F[_]: ConcurrentEffect](
      urls: HttpServiceUrls,
      ec: ExecutionContext): Resource[F, AbtestClient[F]] = {
    BlazeClientBuilder[F](ec).resource.map(cl => new Http4SAbtestClient[F](cl, urls))
  }
}

object AbtestClient {

  trait HttpServiceUrls {

    /**
      * Service URL corresponding to `[[abtest.AbtestAlg]].getAllTestsCached`
      */
    def tests: String

    /**
      * Service URL corresponding to [[analysis.KPIApi]].get
      */
    def kPIs: String

    def test(testId: TestId): String

    def groupMeta(testId: TestId): String

    def featureTests(featureName: FeatureName): String
  }

  /**
    * service urls based on play example routes
    * @param root protocal + host + rootpath e.g. "http://localhost/internal"
    */
  class HttpServiceUrlsPlay(root: String) extends HttpServiceUrls {

    def tests: String = root + "/testsWithFeatures"

    def kPIs: String = root + "/KPIs"

    def groupMeta(testId: TestId) = root + "/tests/" + testId + "/groups/metas"

    def test(testId: TestId) = root + "/tests/" + testId

    def featureTests(featureName: FeatureName): String =
      s"$root/features/$featureName/tests"
  }

  case class ErrorParseJson(errs: Seq[(JsPath, Seq[JsonValidationError])], body: JsValue)
      extends RuntimeException
      with NoStackTrace {
    override def getMessage: String =
      errs.toList.mkString(s"Error parsing json ($body):\n ", "; ", "")
  }

  /**
    * Shortcuts for getting the assigned group only.
    * @param serviceUrl for getting all running tests as of `time`
    */
  def testsWithFeatures[F[_]: ConcurrentEffect](serviceUrl: String,
                                                time: Option[OffsetDateTime])(
      implicit ec: ExecutionContext): F[Vector[(Abtest, Feature)]] =
    Http4SAbtestClient
      .resource[F](new HttpServiceUrlsPlay("mock") {
        override val tests: String = serviceUrl
      }, ec)
      .use(
        _.tests(time).map(_.map { case (Entity(_, test), feature) => (test, feature) }))
}
