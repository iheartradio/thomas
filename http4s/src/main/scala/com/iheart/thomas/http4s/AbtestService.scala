package com.iheart
package thomas
package http4s

import cats.effect.{Async, Resource}
import analysis.{KPIApi, KPIDistribution}
import com.iheart.thomas.model._
import com.typesafe.config.ConfigFactory
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes, Response}
import org.http4s.dsl.Http4sDsl
import play.api.libs.json._
import org.http4s.implicits._
import org.http4s.server.Router
import Formats._
import org.http4s.play._
import lihua.mongo.JsonFormats._

import scala.concurrent.ExecutionContext
import play.api.libs.json.Json.toJson
import cats.implicits._
import Error.{NotFound => APINotFound, _}
import cats.data.{EitherT, OptionT}
import org.http4s.dsl.impl.{OptionalQueryParamDecoderMatcher, QueryParamDecoderMatcher}

class AbtestService[F[_]: Async](
  api: API[APIResult[F, ?]],
  kpiAPI: KPIApi[APIResult[F, ?]]) extends Http4sDsl[F] {

  implicit val jsonObjectEncoder: EntityEncoder[F, JsObject] = implicitly[EntityEncoder[F, JsValue]].narrow

  implicit def decoder[A: Reads]: EntityDecoder[F, A] = jsonOf

  import AbtestService.QueryParamDecoderMatchers._

  def respondOption[T: Format](result: APIResult[F, Option[T]]): F[Response[F]] =
    respond(OptionT(result).getOrElseF(EitherT.leftT(Error.NotFound(None))))

  def respond[T: Format](result: APIResult[F, T]): F[Response[F]] = {

    def errResponse(error: Error): F[Response[F]] = {

      def errorsJson(msgs: Seq[String]): JsObject =
        Json.obj("errors" -> JsArray(msgs.map(JsString)))

      def errorJson(msg: String): JsObject = errorsJson(List(msg))

      def serverError(msg: String): F[Response[F]] = {
        InternalServerError(errorJson(msg): JsValue)
      }

      val validationErrorMsg: ValidationError => String = {
        case InconsistentGroupSizes(sizes) => s"Input group sizes (${sizes.mkString(",")}) add up to more than 1 (${sizes.sum})"
        case InconsistentTimeRange         => "tests must end after start."
        case CannotScheduleTestBeforeNow   => "Cannot schedule a test that starts in the past, confusing history"
        case ContinuationGap(le, st)       => s"Cannot schedule a continuation ($st) after the last test expires ($le)"
        case ContinuationBefore(ls, st)    => s"Cannot schedule a continuation ($st) before the last test starts ($ls)"
        case DuplicatedGroupName           => "group names must be unique."
        case EmptyGroups                   => "There must be at least one group."
        case GroupNameTooLong              => "Group names must be less than 256 chars."
        case GroupNameDoesNotExist(gn)     => s"The group name $gn does not exist in the test."
        case InvalidFeatureName            => s"Feature name can only consist of alphanumeric _, - and ."
        case InvalidAlternativeIdName      => s"AlternativeIdName can only consist of alphanumeric _, - and ."
      }

      error match {
        case ValidationErrors(detail)      => BadRequest(errorsJson(detail.toList.map(validationErrorMsg)))
        case APINotFound(_)                => NotFound()
        case FailedToPersist(msg)          => serverError("Failed to save to DB: " + msg)
        case DBException(t)                => serverError("DB Error" + t.getMessage)
        case DBLastError(t)                => serverError("DB Operation Rejected" + t)
        case CannotToChangePastTest(start) => BadRequest(errorJson(s"Cannot change a test that already started at $start"))
        case ConflictCreation(fn)          => Conflict(errorJson(s"There is another test being created right now, could this one be a duplicate? $fn"))
        case ConflictTest(existing) => Conflict(
          errorJson(s"Cannot start a test on ${existing.data.feature} yet before an existing test") ++
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

    result.value.flatMap(_.fold(
      errResponse ,
      t => Ok(toJson(t))
    ))
  }

  def routes =
    Router( "/internal/" -> internal).orElse(public).orNotFound

  def public = HttpRoutes.of[F] {
    case req @ POST -> Root / "users" / "groups" / "query" =>
       req.as[UserGroupQuery] >>= (ugq => respond(api.getGroupsWithMeta(ugq)))
  }

  def internal = HttpRoutes.of[F] {

    case req @ POST -> Root / "tests" :? auto(a) =>
      req.as[AbtestSpec] >>= (t => respond(api.create(t, a.getOrElse(false))))

    case req @ PUT -> Root / "tests" =>
      req.as[AbtestSpec] >>= (t => respond(api.continue(t)))


    case GET -> Root / "tests" / "history" / LongVar(at) =>
      respond(api.getAllTestsEpoch(Some(at)))

    case GET -> Root / "tests" / LongVar(endAfter) =>
      respond(api.getAllTestsEndAfter(endAfter))

    case GET -> Root / "tests" =>
      respond(api.getAllTests(None))

    case GET -> Root / "tests" / testId =>
      respond(api.getTest(testId))

    case DELETE -> Root / "tests" / testId =>
      respondOption(api.terminate(testId))

    case req @ PUT -> Root / "tests" / testId / "groups" / "metas" =>
      req.as[Map[GroupName, GroupMeta]] >>= ( m => respond(api.addGroupMetas(testId, m)))

    case GET -> Root / "tests" / testId / "groups" / "metas" =>
      respondOption(api.getTestExtras(testId))

    case GET -> Root / "tests" / "cache" :? at(a) =>
      respond(api.getAllTestsCachedEpoch(a))

    case GET -> Root / "features" =>
      respond(api.getAllFeatures)

    case GET -> Root / "features" / feature / "tests" =>
      respond(api.getTestsByFeature(feature))

    case PUT -> Root / "features"/ feature / "groups" /groupName / "overrides" / userId =>
      respond(api.addOverrides(feature, Map(userId -> groupName)))

    case PUT -> Root / "features" / feature / "overridesEligibility" :? ovrrd(o) =>
      respond(api.setOverrideEligibilityIn(feature, o))

    case DELETE -> Root / "features" / feature / "overrides" / userId =>
      respond(api.removeOverrides(feature, userId))

    case DELETE -> Root / "features" / feature / "overrides"  =>
      respond(api.removeAllOverrides(feature))

    case GET -> Root / "features" / feature / "overrides"  =>
      respond(api.getOverrides(feature))

    case req @ POST -> Root / "features" / feature / "overrides"  =>
      req.as[Map[UserId, GroupName]] >>= (m => respond(api.addOverrides(feature, m)))

    case GET -> Root / "KPIs" / name =>
      respondOption(kpiAPI.get(name))

    case req @ POST -> Root / "KPIs" =>
      req.as[KPIDistribution] >>= (k => respond(kpiAPI.upsert(k)))
  }

}


object AbtestService {
  def mongo[F[_]](implicit F: Async[F], ex: ExecutionContext): Resource[F, AbtestService[F]]= {
    import thomas.mongo.idSelector
    for {
      cfg <- Resource.liftF(F.delay(ConfigFactory.load))
      daos <- {
        implicit val c = cfg
        thomas.mongo.daosResource[F]
      }
    } yield {
      implicit val (abtestDAO, abtestExtraDAO, featureDAO, kpiDAO) = daos
      import scala.compat.java8.DurationConverters._
      val ttl = cfg.getDuration("iheart.abtest.get-groups.ttl").toScala
      new AbtestService(new DefaultAPI[APIResult[F, ?]](ttl),
         KPIApi.default)
    }
  }

  object QueryParamDecoderMatchers {
    object auto extends OptionalQueryParamDecoderMatcher[Boolean]("auto")
    object ovrrd extends QueryParamDecoderMatcher[Boolean]("override")
    object at extends OptionalQueryParamDecoderMatcher[Long]("at")
  }
}


