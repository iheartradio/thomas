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
import com.iheart.thomas.http4s.auth.{AuthedEndpointsUtils, AuthenticationAlg}
import com.typesafe.config.Config
import io.estatico.newtype.ops._
import lihua.{Entity, EntityId}
import org.http4s.{FormDataDecoder, QueryParamDecoder}
import org.http4s.FormDataDecoder._
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.impl.{
  FlagQueryParamMatcher,
  OptionalQueryParamDecoderMatcher,
  QueryParamDecoderMatcher
}
import org.http4s.twirl._
import _root_.play.api.libs.json.JsObject

import scala.concurrent.ExecutionContext
import admin.Authorization._
import cats.Functor
import TimeUtil._
import com.iheart.thomas.http4s.AdminUI.AdminUIConfig

class AbtestManagementUI[F[_]: Async: Timer](
    alg: AbtestAlg[F],
    authAlg: AuthenticationAlg[F, AuthImp]
  )(implicit cfg: AdminUIConfig)
    extends AuthedEndpointsUtils[F, AuthImp]
    with Http4sDsl[F] {

  import AbtestManagementUI.Decoders._
  import tsec.authentication._

  implicit val reverseRoutes: ReverseRoutes = ReverseRoutes.apply

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
    SeeOther(testUrl(test).location)

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
        val testData =
          filters.feature
            .fold(tests)(f => tests.filter(_.data.feature == f))
            .groupBy(_.data.feature)
            .map {
              case (fn, tests) =>
                (
                  features.find(_.name == fn).get,
                  tests.sortBy(_.data.start).toList.reverse
                )
            }
            .toList

        val sorted = filters.orderBy match {
          case OrderBy.Alphabetical =>
            testData.sortBy(_._1.name.toLowerCase)
          case OrderBy.Recent =>
            testData
              .sortBy(_._2.head.data.start)
              .reverse

        }

        Ok(index(sorted, features, filters)(UIEnv(u)))
      }.flatten

    def showFeature(
        u: User,
        feature: Feature,
        msg: Option[Either[String, String]] = None
      ) = {
      for {
        tests <- alg.getTestsByFeature(feature.name)
        allUsers <- authAlg.allUsers
        r <- Ok(
          featureForm(
            feature,
            tests.size,
            msg.flatMap(_.left.toOption),
            msg.flatMap(_.right.toOption),
            (allUsers.map(_.username).toSet -- feature.developers.toSet).toList
          )(UIEnv(u))
        )
      } yield r
    }

    def getTestInfo(
        testId: String
      ): F[TestInfo] =
      for {
        test <- get(testId)
        otherFeatureTests <- alg.getTestsByFeature(test.data.feature)
      } yield TestInfo(test, otherFeatureTests)

    implicit class UserAuthSyntax(user: User) {
      def canEdit(testId: String): F[Unit] =
        get(testId).flatMap(canEdit(_))

      def canEdit(test: Entity[Abtest]): F[Unit] =
        alg
          .getFeature(test.data.feature)
          .flatMap(feature => user.check[F](ManageFeature(feature)))

      def canAddTest(fn: FeatureName): F[Unit] =
        alg
          .findFeature(fn)
          .flatMap(
            _.fold(user.check[F](CreateNewFeature))(f =>
              user.check[F](ManageFeature(f))
            )
          )
    }

    roleBasedService(admin.Authorization.testManagerRoles) {

      case GET -> Root / "tests" / "new" :? featureReq(fn) +& scratchConfirmed(
            scratch
          ) asAuthed u =>
        u.canAddTest(fn) *>
          (if (scratch)
             none[Entity[Abtest]].pure[F]
           else
             alg.getLatestTestByFeature(fn))
            .flatMap { followUpCandidate =>
              Ok(newTest(fn, None, None, followUpCandidate)(UIEnv(u)))
            }

      case GET -> Root / "tests" / testId / "new_revision" asAuthed u =>
        get(testId).flatMap(test =>
          u.canEdit(test) *> Ok(
            newRevision(
              test,
              Some(
                test.data.toSpec.copy(
                  start = OffsetDateTime.now,
                  end = None
                )
              )
            )(UIEnv(u))
          )
        )

      case se @ POST -> Root / "features" / feature asAuthed u =>
        se.request
          .as[Feature]
          .redeemWith(
            e => BadRequest(errorMsg(e.getMessage)),
            f => {
              alg
                .getFeature(f.name)
                .flatMap { existing =>
                  if (existing.nonTestSettingsChangedFrom(f))
                    u.check[F](ManageFeature(f))
                  else u.check[F](ManageTestSettings(f))
                } *>
                alg
                  .updateFeature(f)
                  .flatMap(
                    showFeature(u, _, Some(Right("Feature successfully update.")))
                  )
                  .handleErrorWith(e =>
                    showFeature(u, f, Some(Left(displayError(e))))
                  )

            }
          )

      case se @ POST -> Root / "tests" / testId / "new_revision" asAuthed u =>
        get(testId).flatMap { fromTest =>
          se.request
            .as[SpecForm]
            .redeemWith(
              e => BadRequest(editTest(fromTest, Some(displayError(e)))(UIEnv(u))),
              _.toAbtestSpec[F](u, fromTest.data.feature).flatMap { spec =>
                u.canEdit(fromTest) *>
                  (if (fromTest.data.end.fold(true)(_.isAfter(spec.startI)))
                     alg.continue(spec)
                   else alg.create(spec, false))
                    .flatMap(redirectToTest)
                    .handleErrorWith(e =>
                      get(testId).flatMap { t =>
                        BadRequest(
                          newRevision(fromTest, Some(spec), Some(displayError(e)))(
                            UIEnv(u)
                          )
                        )
                      }
                    )
              }
            )
        }

      case GET -> Root / "tests" / testId / "edit" asAuthed u =>
        getTestInfo(testId).flatMap { ti =>
          u.canEdit(ti.test) *> Ok(
            editTest(test = ti.test, followUpO = ti.followUpO)(UIEnv(u))
          )
        }

      case GET -> Root / "tests" / testId / "delete" asAuthed u =>
        u.canEdit(testId) *>
          alg.terminate(testId.coerce[EntityId]).flatMap { ot =>
            val message = ot.fold(s"deleted test $testId")(t =>
              s"terminated running test for feature ${t.data.feature}"
            )

            Ok(redirect(reverseRoutes.tests, s"Successfully $message."))
          }

      case se @ POST -> Root / "tests" / testId asAuthed u =>
        get(testId).flatMap { test =>
          u.canEdit(test) *>
            se.request
              .as[SpecForm]
              .redeemWith(
                e =>
                  get(testId).flatMap { t =>
                    BadRequest(editTest(t, Some(displayError(e)))(UIEnv(u)))
                  },
                _.toAbtestSpec[F](u, test.data.feature).flatMap { spec =>
                  alg
                    .updateTest(
                      testId.coerce[EntityId],
                      spec
                    )
                    .flatMap(redirectToTest)
                    .handleErrorWith(e =>
                      BadRequest(
                        editTest(test, Some(displayError(e)), Some(spec))(UIEnv(u))
                      )
                    )
                }
              )
        }

      case se @ POST -> Root / "features" / feature / "tests" asAuthed u =>
        se.request
          .as[SpecForm]
          .redeemWith(
            e => BadRequest(errorMsg(e.getMessage)),
            _.toAbtestSpec[F](u, feature).flatMap { spec =>
              u.canAddTest(feature) *>
                alg
                  .create(spec, false)
                  .flatMap(test =>
                    SeeOther(
                      testUrl(test).location
                    )
                  )
                  .handleErrorWith { e =>
                    BadRequest(
                      newTest(feature, Some(spec), Some(displayError(e)))(UIEnv(u))
                    )
                  }
            }
          )

    } <+> roleBasedService(admin.Authorization.readableRoles) {
      case GET -> Root / "tests" :? endsAfter(ea) +& feature(fn) +& orderBy(
            ob
          ) asAuthed u =>
        testsList(
          u,
          Filters(
            ea.getOrElse(defaultEndsAfter),
            fn.filter(_ != "_ALL_FEATURES_"),
            ob.getOrElse(OrderBy.Recent)
          )
        )

      case GET -> Root / "features" / feature asAuthed u =>
        alg.getFeature(feature).flatMap(showFeature(u, _))

      case GET -> Root / "tests" / testId asAuthed u =>
        for {
          ti <- getTestInfo(testId)
          feature <- alg.getFeature(ti.test.data.feature)
          r <- Ok(
            showTest(
              ti.test,
              ti.followUpO,
              feature,
              ti.isShuffled
            )(UIEnv(u))
          )
        } yield r
    }
  }

}

object AbtestManagementUI {
  val defaultEndsAfter = OffsetDateTime.now.minusDays(30)

  import enumeratum._
  sealed trait OrderBy extends EnumEntry

  object OrderBy extends Enum[OrderBy] {
    val values = findValues

    case object Alphabetical extends OrderBy
    case object Recent extends OrderBy

  }

  case class Filters(
      endsAfter: OffsetDateTime,
      feature: Option[FeatureName] = None,
      orderBy: OrderBy = OrderBy.Recent)

  case class TestInfo(
      test: Entity[Abtest],
      testsOfFeature: Vector[Entity[Abtest]]) {

    lazy val followUpO: Option[Entity[Abtest]] =
      testsOfFeature.filter(_.data.start.isAfter(test.data.start)).lastOption

    lazy val previousO: Option[Entity[Abtest]] =
      testsOfFeature.filter(_.data.start.isBefore(test.data.start)).headOption

    lazy val isShuffled =
      (previousO.flatMap(_.data.salt), test.data.salt) match {
        case (None, Some(_)) => true
        case (Some(_), None) => true
        case (None, None)    => false
        case (Some(s1), Some(s2)) =>
          s1 != s2
      }
  }

  def fromMongo[F[_]: Timer](
      cfgResourceName: Option[String] = None
    )(implicit F: Concurrent[F],
      ex: ExecutionContext,
      cfg: AdminUIConfig,
      authAlg: AuthenticationAlg[F, AuthImp]
    ): Resource[F, AbtestManagementUI[F]] = {
    MongoResources
      .abtestAlg[F](cfgResourceName)
      .map(new AbtestManagementUI(_, authAlg))
  }

  def fromMongo[F[_]: Timer](
      cfg: Config
    )(implicit F: Concurrent[F],
      ex: ExecutionContext,
      acfg: AdminUIConfig,
      authAlg: AuthenticationAlg[F, AuthImp]
    ): Resource[F, AbtestManagementUI[F]] = {
    MongoResources.abtestAlg[F](cfg).map(new AbtestManagementUI(_, authAlg))
  }

  object Decoders extends CommonFormDecoders {
    import com.iheart.thomas.abtest.json.play.Formats._

    object endsAfter
        extends OptionalQueryParamDecoderMatcher[OffsetDateTime]("endsAfter")

    implicit val orderQP: QueryParamDecoder[OrderBy] =
      QueryParamDecoder.fromUnsafeCast(c => OrderBy.withName(c.value))("OrderBy")

    object feature extends OptionalQueryParamDecoderMatcher[FeatureName]("feature")
    object featureReq extends QueryParamDecoderMatcher[FeatureName]("feature")
    object scratchConfirmed extends FlagQueryParamMatcher("scratch")

    object orderBy extends OptionalQueryParamDecoderMatcher[OrderBy]("orderBy")

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

    case class SpecForm(
        name: TestName,
        start: Option[OffsetDateTime],
        end: Option[OffsetDateTime],
        groups: List[Group],
        requiredTags: List[Tag] = Nil,
        alternativeIdName: Option[MetaFieldName] = None,
        userMetaCriteria: UserMetaCriteria = None,
        reshuffle: Boolean = false,
        segmentRanges: List[GroupRange] = Nil) {
      def toAbtestSpec[F[_]: Functor: Timer](
          u: User,
          feature: FeatureName
        ) = {
        now[F].map { now =>
          AbtestSpec(
            name = name,
            feature = feature,
            author = u.username,
            start = start.getOrElse(now.toOffsetDateTimeSystemDefault),
            end = end,
            groups = groups,
            requiredTags = requiredTags,
            alternativeIdName = alternativeIdName,
            userMetaCriteria = userMetaCriteria,
            reshuffle = reshuffle,
            segmentRanges = segmentRanges
          )
        }

      }
    }

    implicit val abtestSpecFormDecoder: FormDataDecoder[SpecForm] =
      (
        field[TestName]("name"),
        fieldOptional[OffsetDateTime]("start"),
        fieldOptional[OffsetDateTime]("end"),
        list[Group]("groups"),
        tags("requiredTags"),
        fieldOptional[MetaFieldName]("alternativeIdName"),
        fieldOptional[UserMetaCriterion.And]("userMetaCriteria"),
        fieldEither[Boolean]("reshuffle").default(false),
        list[GroupRange]("segmentRanges")
      ).mapN(SpecForm.apply).sanitized

    implicit val FeatureFormDecoder: FormDataDecoder[Feature] = {
      implicit val mapQPD = jsonEntityQueryParamDecoder[Map[String, String]]

      (
        field[FeatureName]("name"),
        fieldOptional[String]("description"),
        list[(String, String)]("overrides").map(_.toMap),
        fieldEither[Boolean]("overrideEligibility").default(false),
        fieldEither[Map[String, String]]("batchOverrides").default(Map.empty),
        listOf[String]("developers")
      ).mapN { (name, desc, overrides, oEFlag, batchOverrides, developers) =>
        Feature(
          name = name,
          description = desc,
          overrides = overrides ++ batchOverrides,
          overrideEligibility = oEFlag,
          developers = developers
        )
      }.sanitized
    }
  }

}
