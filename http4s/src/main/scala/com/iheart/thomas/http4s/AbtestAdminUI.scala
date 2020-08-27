package com.iheart.thomas
package http4s

import java.time.OffsetDateTime

import cats.effect.{Async, Concurrent, Resource, Timer}
import cats.implicits._
import abtest.admin.html._
import html._
import com.iheart.thomas
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.abtest.model.{Abtest, AbtestSpec, Feature}
import com.iheart.thomas.http4s.AbtestService.{
  abtestAlgFromMongo,
  validationErrorMsg
}
import com.typesafe.config.ConfigFactory
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.twirl._
import org.http4s.HttpRoutes

import scala.concurrent.ExecutionContext
import FormDecoders._
import com.iheart.thomas.FeatureName
import com.iheart.thomas.abtest.Error.ValidationErrors
import com.iheart.thomas.http4s.AbtestAdminUI.{
  Filters,
  defaultEndsAfter,
  endsAfter,
  feature,
  featureReq
}
import org.http4s.FormDataDecoder.formEntityDecoder
import org.http4s.dsl.impl.{
  OptionalQueryParamDecoderMatcher,
  QueryParamDecoderMatcher
}
import io.estatico.newtype.ops._
import lihua.{Entity, EntityId}

class AbtestAdminUI[F[_]: Async](alg: AbtestAlg[F]) extends Http4sDsl[F] {

  private def get(testId: String) =
    alg.getTest(testId.coerce[EntityId])

  private def displayError(e: Throwable): String =
    e match {
      case ValidationErrors(detail) =>
        detail.toList.map(validationErrorMsg).mkString("<br/>")
      case _ => e.getMessage
    }

  def redirectToTest(
      test: Entity[Abtest],
      msg: String
    ) =
    Ok(redirect(s"/admin/tests/${test._id}", msg))

  def routes = {
    def testsList(filters: Filters = Filters(defaultEndsAfter)) =
      (
        alg
          .getAllTestsEndAfter(filters.endsAfter),
        alg.getAllFeatures
      ).mapN { (tests, features) =>
        val toShow =
          filters.feature
            .fold(tests)(f => tests.filter(_.data.feature == f))
            .groupBy(_.data.feature)
            .mapValues(_.sortBy(_.data.start).toList)
            .toList
            .sortBy(_._1)
        Ok(index(toShow, features, filters))
      }.flatten

    def showFeature(
        feature: Feature,
        msg: Option[Either[String, String]] = None
      ) =
      alg.getTestsByFeature(feature.name).flatMap { tests =>
        Ok(
          featureForm(
            feature,
            tests.size,
            msg.flatMap(_.left.toOption),
            msg.flatMap(_.right.toOption)
          )
        )
      }

    val adminRoutes =
      HttpRoutes
        .of[F] {
          case GET -> Root / "tests" :? endsAfter(ea) +& feature(fn) =>
            testsList(
              Filters(
                ea.getOrElse(defaultEndsAfter),
                fn.filter(_ != "_ALL_FEATURES_")
              )
            )
          case GET -> Root / "features" / feature =>
            alg.getFeature(feature).flatMap(showFeature(_))

          case req @ POST -> Root / "features" / feature =>
            req
              .as[Feature]
              .redeemWith(
                e => BadRequest(errorMsg(e.getMessage)),
                f =>
                  alg
                    .updateFeature(f)
                    .flatMap(
                      u =>
                        showFeature(u, Some(Right("Feature successfully update.")))
                    )
                    .handleErrorWith(
                      e => showFeature(f, Some(Left(displayError(e))))
                    )
              )

          case GET -> Root / "tests" / "new" :? featureReq(fn) =>
            Ok(newTest(fn, None))

          case GET -> Root / "tests" / testId / "new_revision" =>
            get(testId).flatMap { test =>
              Ok(
                newTest(
                  test.data.feature,
                  Some(
                    test.data.toSpec.copy(
                      start = OffsetDateTime.now,
                      end = None
                    )
                  )
                )
              )
            }

          case GET -> Root / "tests" / testId =>
            for {
              test <- get(testId)
              otherFeatureTests <- alg.getTestsByFeature(test.data.feature)
              feature <- alg.getFeature(test.data.feature)
              r <- Ok(
                showTest(
                  test,
                  otherFeatureTests
                    .filter(_.data.start.isAfter(test.data.start))
                    .headOption,
                  feature.overrides
                )
              )
            } yield r

          case GET -> Root / "tests" / testId / "edit" =>
            get(testId).flatMap { t =>
              Ok(editTest(t))
            }

          case GET -> Root / "tests" / testId / "delete" =>
            alg.terminate(testId.coerce[EntityId]).flatMap { ot =>
              val message = ot.fold(s"deleted test $testId")(
                t => s"terminated running test for feature ${t.data.feature}"
              )

              Ok(redirect("/admin/tests", s"Successfully $message."))
            }

          case req @ POST -> Root / "tests" / testId =>
            req
              .as[AbtestSpec]
              .redeemWith(
                e =>
                  get(testId).flatMap { t =>
                    BadRequest(editTest(t, Some(displayError(e))))
                  },
                spec =>
                  alg
                    .updateTest(testId.coerce[EntityId], spec)
                    .flatMap(
                      redirectToTest(
                        _,
                        s"Successfully updated test for ${spec.feature}"
                      )
                    )
                    .handleErrorWith(
                      e =>
                        get(testId).flatMap { t =>
                          BadRequest(editTest(t, Some(displayError(e)), Some(spec)))
                        }
                    )
              )

          case req @ POST -> Root / "tests" =>
            req
              .as[AbtestSpec]
              .redeemWith(
                e => BadRequest(errorMsg(e.getMessage)),
                spec =>
                  alg
                    .create(spec, false)
                    .flatMap(
                      redirectToTest(
                        _,
                        s"Successfully created a new test for ${spec.feature}"
                      )
                    )
                    .handleErrorWith { e =>
                      BadRequest(
                        newTest(spec.feature, Some(spec), Some(displayError(e)))
                      )
                    }
              )

        }
    Router("/admin/" -> adminRoutes).orNotFound
  }

}

object AbtestAdminUI {
  val defaultEndsAfter = OffsetDateTime.now.minusDays(10)
  case class Filters(
      endsAfter: OffsetDateTime,
      feature: Option[FeatureName] = None)

  def fromMongo[F[_]: Timer](
      implicit F: Concurrent[F],
      ex: ExecutionContext
    ): Resource[F, AbtestAdminUI[F]] = {

    for {
      cfg <- Resource.liftF(F.delay(ConfigFactory.load))
      daos <- thomas.mongo.daosResource[F](cfg)
      alg <- {
        implicit val (c, d) = (cfg, daos)
        abtestAlgFromMongo[F]
      }
    } yield {
      new AbtestAdminUI[F](alg)
    }
  }

  object endsAfter
      extends OptionalQueryParamDecoderMatcher[OffsetDateTime]("endsAfter")

  object feature extends OptionalQueryParamDecoderMatcher[FeatureName]("feature")
  object featureReq extends QueryParamDecoderMatcher[FeatureName]("feature")
}
