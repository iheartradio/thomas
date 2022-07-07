package com.iheart.thomas
package http4s.abtest

import java.time.Instant
import _root_.play.api.libs.json.Json.toJson
import _root_.play.api.libs.json._
import cats.effect.{Async, Resource}
import cats.syntax.all._
import com.iheart.thomas.abtest.Error._
import com.iheart.thomas.abtest.json.play.Formats._
import com.iheart.thomas.abtest.model._
import com.iheart.thomas.abtest.{AbtestAlg, Error}
import Error.{NotFound => APINotFound}
import com.iheart.thomas.http4s.MongoResources
import com.typesafe.config.Config
import lihua.EntityId
import lihua.mongo.JsonFormats._
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.impl.{
  OptionalQueryParamDecoderMatcher,
  QueryParamDecoderMatcher
}
import org.http4s.implicits._
import org.http4s.play._
import org.http4s.server.Router
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes, Response}
import AbtestService.validationErrorMsg
import cats.MonadThrow
import com.iheart.thomas.tracking.{Event, EventLogger}

import scala.concurrent.ExecutionContext

trait AbtestServiceHelper[F[_]] extends Http4sDsl[F] {
  implicit val jsonObjectEncoder: EntityEncoder[F, JsObject] =
    implicitly[EntityEncoder[F, JsValue]].narrow

  def respondOption[T: Format](
      result: F[Option[T]],
      notFoundMsg: String
    )(implicit F: MonadThrow[F]
    ): F[Response[F]] =
    respond(
      result.flatMap(_.liftTo[F](Error.NotFound(notFoundMsg)))
    )

  def respond[T: Format](result: F[T])(implicit F: MonadThrow[F]): F[Response[F]] = {

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
        case e @ DBException(_, _) => serverError("DB Error" + e.getMessage)
        case DBLastError(t)        => serverError("DB Operation Rejected" + t)
        case e @ CannotChangePastTest(_) =>
          BadRequest(errorJson(e.getMessage))
        case CannotChangeGroupSizeWithFollowUpTest(t) =>
          BadRequest(
            errorJson(
              s"Cannot change group sizes for test having follow test ${t._id}"
            )
          )
        case CannotRollback =>
          BadRequest(errorJson(s"Cannot rollback this test"))
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

    result.flatMap(t => Ok(toJson(t))).recoverWith { case e: abtest.Error =>
      errResponse(e)
    }
  }
}

class AbtestService[F[_]: Async](
    api: AbtestAlg[F]
  )(implicit logger: EventLogger[F])
    extends Http4sDsl[F]
    with AbtestServiceHelper[F] {

  implicit def decoder[A: Reads]: EntityDecoder[F, A] = jsonOf
  import AbtestService.QueryParamDecoderMatchers._

  def routes =
    Router("/internal/" -> internal).orElse(public).orNotFound

  def public =
    HttpRoutes.of[F] { case req @ POST -> Root / "users" / "groups" / "query" =>
      req.as[UserGroupQuery] >>= (ugq =>
        respond(
          api
            .getGroupsWithMeta(ugq)
            .flatTap(r => logger(AbTestRequestServed(ugq, r)))
        )
      )
    }

  def readonly: HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "health" =>
        respond(
          api.warmUp.as(Map("status" -> "healthy", "version" -> BuildInfo.version))
        )

      case GET -> Root / "tests" / "history" / LongVar(at) =>
        respond(api.getAllTestsEpoch(Some(at)))
      case GET -> Root / "tests" / LongVar(endAfter) =>
        respond(api.getAllTestsEndAfter(endAfter))

      case GET -> Root / "tests" :? at(atL) +& endAfter(eAL) =>
        respond(
          eAL.fold(api.getAllTests(atL.map(utils.time.toDateTime))) { ea =>
            api.getAllTestsEndAfter(ea)
          }
        )

      case GET -> Root / "testsWithFeatures" :? at(atL) =>
        respond(api.getAllTestsCachedEpoch(atL))

      case GET -> Root / "testsData" :? atEpochMilli(aem) +& durationMillisecond(
            d
          ) =>
        import scala.concurrent.duration._
        respond(
          api.getTestsData(
            Instant.ofEpochMilli(aem),
            d.map(_.millis)
          )
        )

      case GET -> Root / "tests" / testId =>
        respond(api.getTest(EntityId(testId)))

      case GET -> Root / "tests" / "cache" :? at(a) =>
        respond(api.getAllTestsCachedEpoch(a))

      case GET -> Root / "features" =>
        respond(api.getAllFeatureNames)

      case GET -> Root / "features" / feature / "tests" =>
        respond(api.getTestsByFeature(feature))

      case GET -> Root / "features" / feature / "overrides" =>
        respond(api.getOverrides(feature))
    }

  def internal: HttpRoutes[F] = readonly

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

  def fromMongo[F[_]: Async: EventLogger](
      configResourceName: Option[String] = None
    )(implicit
      ex: ExecutionContext
    ): Resource[F, AbtestService[F]] = {
    MongoResources.cfg[F](configResourceName).flatMap(fromMongo[F](_))
  }

  def fromMongo[F[_]: Async: EventLogger](
      cfg: Config
    )(implicit
      ex: ExecutionContext
    ): Resource[F, AbtestService[F]] = {

    for {
      daos <- MongoResources.dAOs(cfg)
      alg <- MongoResources.abtestAlg[F](cfg, daos)
    } yield {
      new AbtestService(alg)
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

case class AbTestRequestServed(req: UserGroupQuery, result: UserGroupQueryResult)
    extends Event
