package com.iheart
package thomas
package http4s

import java.time.Instant

import abtest._
import model._
import com.iheart.thomas.abtest.json.play.Formats._
import cats.effect.{Async, Resource}
import analysis.{KPIModel, KPIModelApi}
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes, Response}
import org.http4s.dsl.Http4sDsl
import _root_.play.api.libs.json._
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.play._
import lihua.mongo.JsonFormats._
import cats.effect.{Concurrent, Timer}

import scala.concurrent.ExecutionContext
import _root_.play.api.libs.json.Json.toJson
import cats.implicits._
import Error.{FeatureCannotBeChanged, NotFound => APINotFound, _}
import com.iheart.thomas.abtest.protocol.UpdateUserMetaCriteriaRequest
import com.iheart.thomas.http4s.AbtestService.validationErrorMsg
import lihua.EntityId
import org.http4s.dsl.impl.{
  OptionalQueryParamDecoderMatcher,
  QueryParamDecoderMatcher
}

class AbtestService[F[_]: Async](
    api: AbtestAlg[F],
    kpiAPI: KPIModelApi[F])
    extends Http4sDsl[F] {

  implicit val jsonObjectEncoder: EntityEncoder[F, JsObject] =
    implicitly[EntityEncoder[F, JsValue]].narrow

  implicit def decoder[A: Reads]: EntityDecoder[F, A] = jsonOf

  import AbtestService.QueryParamDecoderMatchers._

  def respondOption[T: Format](
      result: F[Option[T]],
      notFoundMsg: String
    ): F[Response[F]] =
    respond(
      result.flatMap(_.liftTo[F](Error.NotFound(notFoundMsg)))
    )

  def respond[T: Format](result: F[T]): F[Response[F]] = {

    def errorsJson(msgs: Seq[String]): JsObject =
      Json.obj("errors" -> JsArray(msgs.map(JsString)))

    def errorJson(msg: String): JsObject = errorsJson(List(msg))

    def serverError(msg: String): F[Response[F]] = {
      InternalServerError(errorJson(msg): JsValue)
    }

    def errResponse(error: abtest.Error): F[Response[F]] = {

      error match {
        case ValidationErrors(detail) =>
          BadRequest(errorsJson(detail.toList.map(validationErrorMsg)))
        case APINotFound(_) => NotFound()
        case FailedToPersist(msg) =>
          serverError("Failed to save to DB: " + msg)
        case DBException(t) => serverError("DB Error" + t.getMessage)
        case DBLastError(t) => serverError("DB Operation Rejected" + t)
        case e @ CannotChangePastTest(_) =>
          BadRequest(errorJson(e.getMessage))
        case CannotChangeGroupSizeWithFollowUpTest(t) =>
          BadRequest(
            errorJson(
              s"Cannot change group sizes for test having follow test ${t._id}"
            )
          )
        case FeatureCannotBeChanged =>
          BadRequest(
            errorJson(
              s"Cannot change the feature when updating test."
            )
          )
        case e @ CannotUpdateExpiredTest(_) =>
          BadRequest(errorJson(e.getMessage))
        case FailedToReleaseLock(cause) =>
          serverError("failed to release lock when updating due to " + cause)
        case ConflictCreation(fn, cause) =>
          Conflict(
            errorJson(
              s"Couldn't obtain the lock due to $cause. There might be another test being created right now, could this one be a duplicate? $fn"
            )
          )
        case ConflictTest(existing) =>
          Conflict(
            errorJson(
              s"Cannot start a test on ${existing.data.feature} yet before an existing test"
            ) ++
              Json.obj(
                "testInConflict" -> Json.obj(
                  "id" -> existing._id.value,
                  "name" -> existing.data.name,
                  "start" -> existing.data.start,
                  "ends" -> existing.data.end
                )
              )
          )
      }

    }

    result.flatMap(t => Ok(toJson(t))).recoverWith {
      case e: abtest.Error => errResponse(e)
    }
  }

  def routes =
    Router("/internal/" -> internal).orElse(public).orNotFound

  def public = HttpRoutes.of[F] {
    case req @ POST -> Root / "users" / "groups" / "query" =>
      req.as[UserGroupQuery] >>= (
          ugq => respond(api.getGroupsWithMeta(ugq))
      )
  }

  def internal = HttpRoutes.of[F] {

    case req @ POST -> Root / "tests" :? auto(a) =>
      req.as[AbtestSpec] >>= (
          t => respond(api.create(t, a.getOrElse(false)))
      )

    case req @ POST -> Root / "tests" / "auto" =>
      req.as[AbtestSpec] >>= (
          t => respond(api.create(t, true))
      )

    case req @ PUT -> Root / "tests" =>
      req.as[AbtestSpec] >>= (t => respond(api.continue(t)))

    case GET -> Root / "tests" / "history" / LongVar(at) =>
      respond(api.getAllTestsEpoch(Some(at)))

    case GET -> Root / "tests" / LongVar(endAfter) =>
      respond(api.getAllTestsEndAfter(endAfter))

    case GET -> Root / "tests" :? at(atL) +& endAfter(eAL) =>
      respond(
        eAL.fold(api.getAllTests(atL.map(TimeUtil.toDateTime))) { ea =>
          api.getAllTestsEndAfter(ea)
        }
      )

    case GET -> Root / "testsWithFeatures" :? at(atL) =>
      respond(api.getAllTestsCachedEpoch(atL))

    case GET -> Root / "testsData" :? atEpochMilli(aem) +& durationMillisecond(d) =>
      import scala.concurrent.duration._
      respond(
        api.getTestsData(
          Instant.ofEpochMilli(aem),
          d.map(_.millis)
        )
      )

    case GET -> Root / "tests" / testId =>
      respond(api.getTest(EntityId(testId)))

    case DELETE -> Root / "tests" / testId =>
      respondOption(
        api.terminate(EntityId(testId)),
        s"No test with id $testId"
      )

    case req @ PUT -> Root / "tests" / testId / "groups" / "metas" :? auto(a) =>
      req.as[Map[GroupName, GroupMeta]] >>= (
          m =>
            respond(
              api.addGroupMetas(EntityId(testId), m, a.getOrElse(false))
            )
        )

    case DELETE -> Root / "tests" / testId / "groups" / "metas" :? auto(a) =>
      respond(
        api.removeGroupMetas(EntityId(testId), a.getOrElse(false))
      )

    case GET -> Root / "tests" / "cache" :? at(a) =>
      respond(api.getAllTestsCachedEpoch(a))

    case GET -> Root / "features" =>
      respond(api.getAllFeatures)

    case GET -> Root / "features" / feature / "tests" =>
      respond(api.getTestsByFeature(feature))

    case PUT -> Root / "features" / feature / "groups" / groupName / "overrides" / userId =>
      respond(api.addOverrides(feature, Map(userId -> groupName)))

    case PUT -> Root / "features" / feature / "overridesEligibility" :? ovrrd(
          o
        ) =>
      respond(api.setOverrideEligibilityIn(feature, o))

    case DELETE -> Root / "features" / feature / "overrides" / userId =>
      respond(api.removeOverrides(feature, userId))

    case DELETE -> Root / "features" / feature / "overrides" =>
      respond(api.removeAllOverrides(feature))

    case GET -> Root / "features" / feature / "overrides" =>
      respond(api.getOverrides(feature))

    case req @ POST -> Root / "features" / feature / "overrides" =>
      req.as[Map[UserId, GroupName]] >>= (
          m => respond(api.addOverrides(feature, m))
      )

    case GET -> Root / "KPIs" / name =>
      respondOption(kpiAPI.get(name), s"No Kpi under name $name")

    case req @ POST -> Root / "KPIs" =>
      req.as[KPIModel] >>= (k => respond(kpiAPI.upsert(k)))

    case req @ PUT -> Root / "tests" / testId / "userMetaCriteria" =>
      req.as[UpdateUserMetaCriteriaRequest] >>= { r =>
        respond(
          api.updateUserMetaCriteria(EntityId(testId), r.criteria, r.auto)
        )
      }
  }

}

object AbtestService {

  val validationErrorMsg: ValidationError => String = {
    case InconsistentGroupSizes(sizes) =>
      s"Input group sizes (${sizes.mkString(",")}) add up to more than 1 (${sizes.sum})"
    case InconsistentTimeRange => "tests must end after start."
    case CannotScheduleTestBeforeNow =>
      "Cannot schedule a test that starts in the past, confusing history"
    case ContinuationGap(le, st) =>
      s"Cannot schedule a continuation ($st) after the last test expires ($le)"
    case ContinuationBefore(ls, st) =>
      s"Cannot schedule a continuation ($st) before the last test starts ($ls)"
    case DuplicatedGroupName => "group names must be unique."
    case EmptyGroups         => "There must be at least one group."
    case GroupNameTooLong    => "Group names must be less than 256 chars."
    case GroupNameDoesNotExist(gn) =>
      s"The group name $gn does not exist in the test."
    case EmptyGroupMeta => s"Cannot update with an empty group meta"
    case InvalidFeatureName =>
      s"Feature name can only consist of alphanumeric _, - and ."
    case InvalidAlternativeIdName =>
      s"AlternativeIdName can only consist of alphanumeric _, - and ."
    case EmptyUserId => s"User id cannot be an empty string."
  }

  def fromMongo[F[_]: Timer](
      configResourceName: Option[String] = None
    )(implicit F: Concurrent[F],
      ex: ExecutionContext
    ): Resource[F, AbtestService[F]] = {

    for {
      cfg <- MongoResources.cfg[F](configResourceName)
      daos <- MongoResources.dAOs(cfg)
      alg <- MongoResources.abtestAlg[F](cfg, daos)
    } yield {
      implicit val (_, _, kpiDAO) = daos
      new AbtestService(alg, KPIModelApi.default[F])
    }
  }

  object QueryParamDecoderMatchers {
    object auto extends OptionalQueryParamDecoderMatcher[Boolean]("auto")
    object ovrrd extends QueryParamDecoderMatcher[Boolean]("override")
    object at extends OptionalQueryParamDecoderMatcher[Long]("at")
    object endAfter extends OptionalQueryParamDecoderMatcher[Long]("endAfter")
    object atEpochMilli extends QueryParamDecoderMatcher[Long]("atEpochMilli")
    object durationMillisecond
        extends OptionalQueryParamDecoderMatcher[Long]("durationMillisecond")
  }
}
