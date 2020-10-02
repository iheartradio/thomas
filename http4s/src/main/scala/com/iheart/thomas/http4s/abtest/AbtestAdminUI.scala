package com.iheart.thomas
package http4s
package abtest

import java.time.OffsetDateTime

import cats.data.Validated.Valid
import cats.effect.{Async, Concurrent, Resource, Timer}
import cats.implicits._
import com.iheart.thomas.{FeatureName, GroupName}
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.abtest.Error.ValidationErrors
import com.iheart.thomas.abtest.admin.html._
import com.iheart.thomas.abtest.model.Abtest.Specialization
import com.iheart.thomas.abtest.model._
import com.iheart.thomas.html.{redirect, errorMsg}
import com.iheart.thomas.http4s.abtest.AbtestAdminUI._
import com.iheart.thomas.http4s.abtest.AbtestService.validationErrorMsg
import com.typesafe.config.Config
import io.estatico.newtype.ops._
import lihua.{Entity, EntityId}
import org.http4s.{FormDataDecoder, HttpRoutes, QueryParamDecoder}
import org.http4s.FormDataDecoder._
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.impl.{
  OptionalQueryParamDecoderMatcher,
  QueryParamDecoderMatcher
}
import org.http4s.twirl._
import play.api.libs.json.JsObject

import scala.concurrent.ExecutionContext

class AbtestAdminUI[F[_]: Async](
    alg: AbtestAlg[F],
    rootPath: String)
    extends Http4sDsl[F] {
  private implicit val reverseRoutes = new ReverseRoutes(rootPath)
  import AbtestAdminUI.Decoders._

  private def displayError(e: Throwable): String =
    e match {
      case ValidationErrors(detail) =>
        detail.toList.map(validationErrorMsg).mkString("<br/>")
      case _ => e.getMessage
    }

  val routes = {

    def redirectToTest(
        test: Entity[Abtest],
        msg: String
      ) =
      Ok(redirect(s"${reverseRoutes.tests}/${test._id}", msg))

    def get(testId: String) =
      alg.getTest(testId.coerce[EntityId])

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

    def getTestAndFollowUp(
        testId: String
      ): F[(Entity[Abtest], Option[Entity[Abtest]])] =
      for {
        test <- get(testId)
        otherFeatureTests <- alg.getTestsByFeature(test.data.feature)
      } yield (
        test,
        otherFeatureTests
          .filter(_.data.start.isAfter(test.data.start))
          .headOption
      )

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
                    u => showFeature(u, Some(Right("Feature successfully update.")))
                  )
                  .handleErrorWith(e => showFeature(f, Some(Left(displayError(e)))))
            )

        case GET -> Root / "tests" / "new" :? featureReq(fn) =>
          Ok(newTest(fn, None))

        case GET -> Root / "tests" / testId / "new_revision" =>
          get(testId).flatMap { test =>
            Ok(
              newRevision(
                test,
                Some(
                  test.data.toSpec.copy(
                    start = OffsetDateTime.now,
                    end = None
                  )
                )
              )
            )
          }

        case req @ POST -> Root / "tests" / testId / "new_revision" =>
          get(testId).flatMap { fromTest =>
            req
              .as[AbtestSpec]
              .redeemWith(
                e => BadRequest(editTest(fromTest, Some(displayError(e)))),
                spec =>
                  (if (fromTest.data.end.fold(true)(_.isAfter(spec.startI)))
                     alg.continue(spec)
                   else alg.create(spec, false))
                    .flatMap(
                      redirectToTest(
                        _,
                        s"Successfully created test for ${spec.feature}"
                      )
                    )
                    .handleErrorWith(
                      e =>
                        get(testId).flatMap { t =>
                          BadRequest(
                            newRevision(fromTest, Some(spec), Some(displayError(e)))
                          )
                        }
                    )
              )
          }
        case GET -> Root / "tests" / testId =>
          for {
            p <- getTestAndFollowUp(testId)
            (test, followUpO) = p
            feature <- alg.getFeature(test.data.feature)
            r <- Ok(
              showTest(
                test,
                followUpO,
                feature.overrides
              )
            )
          } yield r

        case GET -> Root / "tests" / testId / "edit" =>
          getTestAndFollowUp(testId).flatMap {
            case (test, followUpO) =>
              Ok(editTest(test = test, followUpO = followUpO))
          }

        case GET -> Root / "tests" / testId / "delete" =>
          alg.terminate(testId.coerce[EntityId]).flatMap { ot =>
            val message = ot.fold(s"deleted test $testId")(
              t => s"terminated running test for feature ${t.data.feature}"
            )

            Ok(redirect(reverseRoutes.tests, s"Successfully $message."))
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
  }

}

object AbtestAdminUI {
  val defaultEndsAfter = OffsetDateTime.now.minusDays(30)
  case class Filters(
      endsAfter: OffsetDateTime,
      feature: Option[FeatureName] = None)

  def fromMongo[F[_]: Timer](
      rootPath: String,
      cfgResourceName: Option[String] = None
    )(implicit F: Concurrent[F],
      ex: ExecutionContext
    ): Resource[F, AbtestAdminUI[F]] = {
    MongoResources.abtestAlg[F](cfgResourceName).map(new AbtestAdminUI(_, rootPath))
  }

  def fromMongo[F[_]: Timer](
      rootPath: String,
      cfg: Config
    )(implicit F: Concurrent[F],
      ex: ExecutionContext
    ): Resource[F, AbtestAdminUI[F]] = {
    MongoResources.abtestAlg[F](cfg).map(new AbtestAdminUI(_, rootPath))
  }

  object Decoders extends CommonFormDecoders {
    import com.iheart.thomas.abtest.json.play.Formats._

    object endsAfter
        extends OptionalQueryParamDecoderMatcher[OffsetDateTime]("endsAfter")

    object feature extends OptionalQueryParamDecoderMatcher[FeatureName]("feature")
    object featureReq extends QueryParamDecoderMatcher[FeatureName]("feature")

    implicit val userMetaCriteriaQueryParamDecoder
        : QueryParamDecoder[UserMetaCriterion.And] =
      jsonEntityQueryParamDecoder

    implicit val specializationQueryParamDecoder: QueryParamDecoder[Specialization] =
      jsonEntityQueryParamDecoder

    implicit def tags(key: String): FormDataDecoder[List[Tag]] =
      FormDataDecoder[List[Tag]] { map =>
        Valid(
          map
            .get(key)
            .flatMap(_.headOption)
            .map(_.split(",").toList.map(_.trim))
            .getOrElse(Nil)
        )
      }

    implicit val groupFormDecoder: FormDataDecoder[Group] =
      (
        field[GroupName]("name"),
        field[GroupSize]("size"),
        fieldOptional[JsObject]("meta")
      ).mapN(Group.apply)

    implicit val groupRangeFormDecoder: FormDataDecoder[GroupRange] =
      (
        field[BigDecimal]("start"),
        field[BigDecimal]("end")
      ).mapN(GroupRange.apply)

    implicit val AbtestSpecFormDecoder: FormDataDecoder[AbtestSpec] =
      (
        field[TestName]("name"),
        field[FeatureName]("feature"),
        field[String]("author"),
        field[OffsetDateTime]("start"),
        fieldOptional[OffsetDateTime]("end"),
        list[Group]("groups"),
        tags("requiredTags"),
        fieldOptional[MetaFieldName]("alternativeIdName"),
        fieldOptional[UserMetaCriterion.And]("userMetaCriteria"),
        fieldEither[Boolean]("reshuffle").default(false),
        list[GroupRange]("segmentRanges"),
        none[Abtest.Specialization].pure[FormDataDecoder],
        Map.empty[GroupName, GroupMeta].pure[FormDataDecoder]
      ).mapN(AbtestSpec.apply).sanitized

    implicit val FeatureFormDecoder: FormDataDecoder[Feature] = {
      implicit val mapQPD = jsonEntityQueryParamDecoder[Map[String, String]]

      (
        field[FeatureName]("name"),
        fieldOptional[String]("description"),
        list[(String, String)]("overrides").map(_.toMap),
        fieldEither[Boolean]("overrideEligibility").default(false),
        fieldEither[Map[String, String]]("batchOverrides").default(Map.empty)
      ).mapN { (name, desc, overrides, oEFlag, batchOverrides) =>
        Feature(
          name = name,
          description = desc,
          overrides = overrides ++ batchOverrides,
          overrideEligibility = oEFlag
        )
      }.sanitized
    }
  }

}
