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
import com.iheart.thomas.admin.User
import com.iheart.thomas.html.{errorMsg, redirect}
import com.iheart.thomas.http4s.abtest.AbtestManagementUI._
import com.iheart.thomas.http4s.abtest.AbtestService.validationErrorMsg
import com.iheart.thomas.http4s.auth.AuthedEndpointsUtils
import com.typesafe.config.Config
import io.estatico.newtype.ops._
import lihua.{Entity, EntityId}
import org.http4s.{FormDataDecoder, QueryParamDecoder}
import org.http4s.FormDataDecoder._
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.impl.{
  OptionalQueryParamDecoderMatcher,
  QueryParamDecoderMatcher
}
import org.http4s.twirl._
import play.api.libs.json.JsObject

import scala.concurrent.ExecutionContext

class AbtestManagementUI[F[_]: Async](
    alg: AbtestAlg[F]
  )(implicit reverseRoutes: ReverseRoutes)
    extends AuthedEndpointsUtils[F, AuthImp]
    with Http4sDsl[F] {

  import AbtestManagementUI.Decoders._
  import tsec.authentication._

  private def displayError(e: Throwable): String =
    e match {
      case ValidationErrors(detail) =>
        detail.toList.map(validationErrorMsg).mkString("<br/>")
      case _ => e.getMessage
    }
  def testUrl(test: Entity[Abtest]) =
    s"${reverseRoutes.tests}/${test._id}"

  def redirectToTest(
      test: Entity[Abtest],
      msg: String
    ) =
    Ok(redirect(testUrl(test), msg))

  def redirectToTest(
      test: Entity[Abtest]
    ) =
    redirectTo(testUrl(test))

  val routes = {

    def get(testId: String) =
      alg.getTest(testId.coerce[EntityId])

    def testsList(
        u: User,
        filters: Filters = Filters(defaultEndsAfter)
      ) =
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
        Ok(index(u, toShow, features, filters))
      }.flatten

    def showFeature(
        u: User,
        feature: Feature,
        msg: Option[Either[String, String]] = None
      ) =
      alg.getTestsByFeature(feature.name).flatMap { tests =>
        Ok(
          featureForm(
            u,
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

    roleBasedService(auth.Permissions.readableRoles) {
      case GET -> Root / "tests" :? endsAfter(ea) +& feature(fn) asAuthed u =>
        testsList(
          u,
          Filters(
            ea.getOrElse(defaultEndsAfter),
            fn.filter(_ != "_ALL_FEATURES_")
          )
        )
      case GET -> Root / "features" / feature asAuthed u =>
        alg.getFeature(feature).flatMap(showFeature(u, _))

      case GET -> Root / "tests" / testId asAuthed u =>
        for {
          p <- getTestAndFollowUp(testId)
          (test, followUpO) = p
          feature <- alg.getFeature(test.data.feature)
          r <- Ok(
            showTest(
              u,
              test,
              followUpO,
              feature.overrides
            )
          )
        } yield r
    } <+> roleBasedService(auth.Permissions.testManagerRoles) {

      case GET -> Root / "tests" / "new" :? featureReq(fn) asAuthed u =>
        Ok(newTest(u, fn, None))

      case GET -> Root / "tests" / testId / "new_revision" asAuthed u =>
        get(testId).flatMap { test =>
          Ok(
            newRevision(
              u,
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

      case se @ POST -> Root / "features" / feature asAuthed u =>
        se.request
          .as[Feature]
          .redeemWith(
            e => BadRequest(errorMsg(e.getMessage)),
            f =>
              alg
                .updateFeature(f)
                .flatMap(
                  showFeature(u, _, Some(Right("Feature successfully update.")))
                )
                .handleErrorWith(e => showFeature(u, f, Some(Left(displayError(e)))))
          )

      case se @ POST -> Root / "tests" / testId / "new_revision" asAuthed u =>
        get(testId).flatMap { fromTest =>
          se.request
            .as[AbtestSpec]
            .redeemWith(
              e => BadRequest(editTest(u, fromTest, Some(displayError(e)))),
              spec =>
                (if (fromTest.data.end.fold(true)(_.isAfter(spec.startI)))
                   alg.continue(spec)
                 else alg.create(spec, false))
                  .flatMap(redirectToTest)
                  .handleErrorWith(e =>
                    get(testId).flatMap { t =>
                      BadRequest(
                        newRevision(u, fromTest, Some(spec), Some(displayError(e)))
                      )
                    }
                  )
            )
        }

      case GET -> Root / "tests" / testId / "edit" asAuthed u =>
        getTestAndFollowUp(testId).flatMap {
          case (test, followUpO) =>
            Ok(editTest(u, test = test, followUpO = followUpO))
        }

      case GET -> Root / "tests" / testId / "delete" asAuthed _ =>
        alg.terminate(testId.coerce[EntityId]).flatMap { ot =>
          val message = ot.fold(s"deleted test $testId")(t =>
            s"terminated running test for feature ${t.data.feature}"
          )

          Ok(redirect(reverseRoutes.tests, s"Successfully $message."))
        }

      case se @ POST -> Root / "tests" / testId asAuthed u =>
        se.request
          .as[AbtestSpec]
          .redeemWith(
            e =>
              get(testId).flatMap { t =>
                BadRequest(editTest(u, t, Some(displayError(e))))
              },
            spec =>
              alg
                .updateTest(testId.coerce[EntityId], spec)
                .flatMap(redirectToTest)
                .handleErrorWith(e =>
                  get(testId).flatMap { t =>
                    BadRequest(editTest(u, t, Some(displayError(e)), Some(spec)))
                  }
                )
          )

      case se @ POST -> Root / "tests" asAuthed u =>
        se.request
          .as[AbtestSpec]
          .redeemWith(
            e => BadRequest(errorMsg(e.getMessage)),
            spec =>
              alg
                .create(spec, false)
                .flatMap(test =>
                  redirectTo(
                    testUrl(test)
                  )
                )
                .handleErrorWith { e =>
                  BadRequest(
                    newTest(u, spec.feature, Some(spec), Some(displayError(e)))
                  )
                }
          )

    }
  }

}

object AbtestManagementUI {
  val defaultEndsAfter = OffsetDateTime.now.minusDays(30)
  case class Filters(
      endsAfter: OffsetDateTime,
      feature: Option[FeatureName] = None)

  def fromMongo[F[_]: Timer](
      cfgResourceName: Option[String] = None
    )(implicit F: Concurrent[F],
      ex: ExecutionContext,
      rr: ReverseRoutes
    ): Resource[F, AbtestManagementUI[F]] = {
    MongoResources
      .abtestAlg[F](cfgResourceName)
      .map(new AbtestManagementUI(_))
  }

  def fromMongo[F[_]: Timer](
      cfg: Config
    )(implicit F: Concurrent[F],
      ex: ExecutionContext,
      rr: ReverseRoutes
    ): Resource[F, AbtestManagementUI[F]] = {
    MongoResources.abtestAlg[F](cfg).map(new AbtestManagementUI(_))
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
