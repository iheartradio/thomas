/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package client

import java.time.Instant
import cats.{Functor, MonadError, MonadThrow}
import cats.effect._
import com.iheart.thomas.abtest.model._
import lihua.Entity
import _root_.play.api.libs.json._
import cats.syntax.all._
import com.iheart.thomas.abtest.{TestsDataProvider, Error, TestsData}
import com.iheart.thomas.abtest.json.play.Formats._
import com.iheart.thomas.abtest.protocol.UpdateUserMetaCriteriaRequest

import scala.concurrent.ExecutionContext
import scala.util.control.NoStackTrace
import com.iheart.thomas.client.AbtestClient.HttpServiceUrls
import org.http4s.blaze.client.BlazeClientBuilder

import scala.concurrent.duration.FiniteDuration

trait ReadOnlyAbtestClient[F[_]] extends TestsDataProvider[F] {
  def tests(asOf: Option[Instant] = None): F[Vector[(Entity[Abtest], Feature)]]

  def featureTests(feature: FeatureName): F[Vector[Entity[Abtest]]]

  def getTest(tid: TestId): F[Entity[Abtest]]

  def test(
      feature: FeatureName,
      asOf: Option[Instant] = None
    )(implicit F: Functor[F]
    ): F[Option[Entity[Abtest]]] =
    tests(asOf).map(_.collectFirst {
      case (test, Feature(`feature`, _, _, _, _, _, _, _)) => test
    })

  def featureLatestTest(
      feature: FeatureName
    )(implicit F: MonadError[F, Throwable]
    ): F[Entity[Abtest]] =
    featureTests(feature).flatMap(
      _.headOption.liftTo[F](Error.NotFound(s"No tests found under $feature"))
    )

  def tidOrFeatureOp[A](
      tidOrFeature: Either[TestId, FeatureName]
    )(f: TestId => F[A]
    )(implicit F: MonadThrow[F]
    ): F[A] = tidOrFeature.fold(f, featureLatestTest(_).flatMap(t => f(t._id)))

  def getUserMetaCriteria(
      tidOrFeature: Either[TestId, FeatureName]
    )(implicit F: MonadThrow[F]
    ): F[UserMetaCriteria] =
    tidOrFeatureOp(tidOrFeature)(getTest(_)).map(_.data.userMetaCriteria)

  def getGroupMeta(
      tidOrFeature: Either[TestId, FeatureName]
    )(implicit F: MonadThrow[F]
    ): F[Map[GroupName, GroupMeta]] =
    tidOrFeatureOp(tidOrFeature)(getTest(_)).map(_.data.getGroupMetas)

}

trait AbtestClient[F[_]] extends ReadOnlyAbtestClient[F] {

  def addGroupMeta(
      tidOrFeature: Either[TestId, FeatureName],
      gm: JsObject,
      auto: Boolean
    ): F[Entity[Abtest]]

  def removeGroupMetas(
      tidOrFeature: Either[TestId, FeatureName],
      auto: Boolean
    ): F[Entity[Abtest]]

  def updateUserMetaCriteria(
      tidOrFeature: Either[TestId, FeatureName],
      userMetaCriteria: UserMetaCriteria,
      auto: Boolean
    ): F[Entity[Abtest]]
}

import org.http4s.client.{Client => HClient}

class Http4SAbtestClient[F[_]: Async](
    c: HClient[F],
    urls: HttpServiceUrls)
    extends PlayJsonHttp4sClient[F](c)
    with AbtestClient[F] {
  import org.http4s.{Method, Uri}
  import Method._

  implicit def stringToUri(str: String) = Uri.unsafeFromString(str)

  def tests(asOf: Option[Instant] = None): F[Vector[(Entity[Abtest], Feature)]] = {
    val baseUrl: Uri = Uri.unsafeFromString(urls.tests)
    expect(
      GET(baseUrl +?? ("at" -> asOf.map(ao => ao.toEpochMilli.toString)))
    )
  }

  def updateUserMetaCriteria(
      tidOrFeature: Either[TestId, FeatureName],
      userMetaCriteria: UserMetaCriteria,
      auto: Boolean
    ): F[Entity[Abtest]] =
    tidOrFeatureOp(tidOrFeature) { testId =>
      expect(
        PUT(
          UpdateUserMetaCriteriaRequest(userMetaCriteria, auto),
          stringToUri(urls.userMetaCriteria(testId))
        )
      )
    }

  def getTestsData(
      at: Instant,
      duration: Option[FiniteDuration]
    ): F[TestsData] = {
    val url = stringToUri(urls.testsData) +?
      ("atEpochMilli" -> at.toEpochMilli.toString) +?? ("durationMillisecond" ->
        duration.map(_.toMillis.toString))
    expect(GET(url))
  }

  def getTest(tid: TestId): F[Entity[Abtest]] =
    c.expect[Entity[Abtest]](GET(urls.test(tid)))

  def addGroupMeta(
      tidOrFeature: Either[TestId, FeatureName],
      gm: JsObject,
      auto: Boolean
    ): F[Entity[Abtest]] =
    tidOrFeatureOp(tidOrFeature) { tid =>
      expect(PUT(gm, stringToUri(urls.groupMeta(tid)) +? ("auto" -> auto)))
    }

  def removeGroupMetas(
      tidOrFeature: Either[TestId, FeatureName],
      auto: Boolean
    ): F[Entity[Abtest]] =
    tidOrFeatureOp(tidOrFeature) { tid =>
      expect(DELETE(stringToUri(urls.groupMeta(tid)) +? ("auto" -> auto)))
    }

  def featureTests(feature: FeatureName): F[Vector[Entity[Abtest]]] =
    expect(GET(urls.featureTests(feature)))

  def getGroupMeta(
      tidOrFeature: Either[TestId, FeatureName]
    ): F[Map[GroupName, GroupMeta]] =
    tidOrFeatureOp(tidOrFeature) { tid =>
      getTest(tid).map(_.data.getGroupMetas)
    }
}

object Http4SAbtestClient {
  def resource[F[_]: Async](
      urls: HttpServiceUrls,
      ec: ExecutionContext
    ): Resource[F, AbtestClient[F]] = {
    BlazeClientBuilder[F](ec).resource.map(cl => new Http4SAbtestClient[F](cl, urls))
  }

  def readOnlyResource[F[_]: Async](
      urls: HttpServiceUrls,
      ec: ExecutionContext
    ): Resource[F, ReadOnlyAbtestClient[F]] = {
    BlazeClientBuilder[F](ec).resource.map(cl => new Http4SAbtestClient[F](cl, urls))
  }
}

object AbtestClient {

  trait HttpServiceUrls {

    /** Service URL corresponding to `[[abtest.AbtestAlg]].getAllTestsCached`
      */
    def tests: String

    def testsData: String

    def test(testId: TestId): String

    def groupMeta(testId: TestId): String

    def userMetaCriteria(testId: TestId): String

    def featureTests(featureName: FeatureName): String
  }

  /** service urls based on play example routes
    * @param root
    *   protocal + host + rootpath e.g. "http://localhost/internal"
    */
  class HttpServiceUrlsPlay(root: String) extends HttpServiceUrls {

    def tests: String = root + "/testsWithFeatures"

    def testsData: String = root + "/testsData"

    def groupMeta(testId: TestId) = root + "/tests/" + testId + "/groups/metas"

    def userMetaCriteria(testId: TestId) =
      root + "/tests/" + testId + "/userMetaCriteria"

    def test(testId: TestId) = root + "/tests/" + testId

    def featureTests(featureName: FeatureName): String =
      s"$root/features/$featureName/tests"
  }

  case class ErrorParseJson(
      errs: Seq[(JsPath, Seq[JsonValidationError])],
      body: JsValue)
      extends RuntimeException
      with NoStackTrace {
    override def getMessage: String =
      errs.toList.mkString(s"Error parsing json ($body):\n ", "; ", "")
  }

  /** Shortcuts for getting the assigned group only.
    * @param serviceRootUrl
    *   for getting all running tests as of `time`
    */
  def testsData[F[_]: Async](
                              serviceRootUrl: String,
                              time: Instant,
                              duration: Option[FiniteDuration]
    )(implicit ec: ExecutionContext
    ): F[TestsData] = {
    val root = serviceRootUrl.replace("/testsWithFeatures", "") //for legacy support.
    Http4SAbtestClient
      .readOnlyResource[F](
        new HttpServiceUrlsPlay(root),
        ec
      )
      .use(_.getTestsData(time, duration))
  }
}
