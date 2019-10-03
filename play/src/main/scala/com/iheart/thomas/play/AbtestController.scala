/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package play
import abtest._
import Formats._
import model._
import cats.effect._
import Error.{NotFound => APINotFound, _}
import AbtestController.{Alerter, InvalidRequest}
import _root_.play.api.libs.json._
import _root_.play.api.mvc._
import cats.implicits._
import cats.effect.implicits._

import scala.concurrent.Future
import com.iheart.thomas.analysis.{KPIApi, KPIDistribution}
import lihua.{Entity, EntityId}
import lihua.mongo.JsonFormats._

import scala.util.control.NoStackTrace

class AbtestController[F[_]](
    api: AbtestAlg[F],
    kpiAPI: KPIApi[F],
    components: ControllerComponents,
    alerter: Option[Alerter[F]]
)(
    implicit
    F: Effect[F]
) extends AbstractController(components) {
  val thr = new HttpResults[F](alerter)
  import thr._

  protected def toResult[Resp: Writes](ar: F[Resp]): Future[Result] = {
    ar.map(t => Ok(Json.toJson(t)))
      .recoverWith {
        case e: Error => errorResult(alerter)(e)
        case InvalidRequest(m) =>
          BadRequest(errorJson(m)).pure[F]
      }
      .toIO
      .unsafeToFuture
  }

  def action[Resp: Writes](ar: F[Resp]): Action[AnyContent] = Action.async {
    toResult(ar)
  }

  protected def jsonAction[Req: Reads, Resp: Writes](f: Req => F[Resp]) =
    Action.async[JsValue](parse.tolerantJson) { req =>
      req.body
        .validate[Req]
        .fold(
          errs => badRequest(errs.map(_.toString): _*).toIO.unsafeToFuture(),
          rq => toResult(f(rq))
        )
    }

  protected def liftOption[T](fo: F[Option[T]], notFoundMsg: String): F[T] =
    fo.flatMap(_.liftTo[F](APINotFound(notFoundMsg)))

  def getKPIDistribution(name: String) = action(
    liftOption(kpiAPI.get(name), s"Cannot find KPI named $name")
  )

  val updateKPIDistribution = jsonAction { (kpi: KPIDistribution) =>
    kpiAPI.upsert(kpi)
  }

  def get(id: String) = action(api.getTest(EntityId(id)))

  def getByFeature(feature: FeatureName) = action(api.getTestsByFeature(feature))

  def getAllFeatures = action(api.getAllFeatures)

  def terminate(id: String) = action(api.terminate(EntityId(id)))

  def getAllTests(at: Option[Long], endAfter: Option[Long]) = action {
    if (endAfter.isDefined && at.isDefined) {
      F.raiseError[Vector[Entity[Abtest]]](
        InvalidRequest("Cannot specify both at and endAfter"))
    } else
      endAfter.fold(api.getAllTests(at.map(TimeUtil.toDateTime))) { ea =>
        api.getAllTestsEndAfter(ea)
      }

  }

  def getAllTestsCached(at: Option[Long]) = action {
    api.getAllTestsCachedEpoch(at)
  }

  def create(autoResolveConflict: Boolean): Action[JsValue] =
    jsonAction((t: AbtestSpec) => api.create(t, autoResolveConflict))

  val create: Action[JsValue] = create(false)

  val createAuto: Action[JsValue] = create(true)

  val continue: Action[JsValue] = jsonAction((t: AbtestSpec) => api.continue(t))

  def getGroups(userId: UserId, at: Option[Long], userTags: Option[List[Tag]] = None) =
    action {
      val time = at.map(TimeUtil.toDateTime)
      api.getGroups(userId,
                    time,
                    userTags.map(_.flatMap(_.split(",").map(_.trim))).getOrElse(Nil))
    }

  def parseEpoch(dateTime: String) = Action {
    TimeUtil
      .parse(dateTime)
      .map(t => Ok(t.toEpochSecond.toString))
      .getOrElse(BadRequest("Wrong Format"))
  }

  def addOverride(feature: FeatureName, userId: UserId, groupName: GroupName) =
    action {
      api.addOverrides(feature, Map(userId -> groupName))
    }

  def setOverrideEligibilityIn(feature: FeatureName, overrideEligibility: Boolean) =
    action {
      api.setOverrideEligibilityIn(feature, overrideEligibility)
    }

  def addOverrides(feature: FeatureName) = jsonAction {
    (overrides: Map[UserId, GroupName]) =>
      api.addOverrides(feature, overrides)
  }

  def addGroupMetas(testId: String, auto: Boolean) =
    jsonAction((metas: Map[GroupName, GroupMeta]) =>
      api.addGroupMetas(EntityId(testId), metas, auto))

  def removeGroupMetas(testId: String, auto: Boolean) = action {
    api.removeGroupMetas(EntityId(testId), auto)
  }

  //for legacy support
  def getGroupMetas(testId: String) = action {
    api.getTest(EntityId(testId)).map(_.data.groupMetas)
  }

  val getGroupsWithMeta = jsonAction(
    (query: UserGroupQuery) => api.getGroupsWithMeta(query))

  def removeOverride(feature: FeatureName, userId: UserId) = action {
    api.removeOverrides(feature, userId)
  }

  def removeAllOverrides(feature: FeatureName) = action {
    api.removeAllOverrides(feature)
  }

  def getOverrides(feature: FeatureName) = action {
    api.getOverrides(feature)
  }

}

class HttpResults[F[_]](alerter: Option[Alerter[F]])(implicit F: Async[F]) {

  def errorJson(msgs: Seq[String]): JsObject =
    Json.obj("errors" -> JsArray(msgs.map(JsString)))

  def errorJson(msg: String): JsObject = errorJson(List(msg))

  import _root_.play.api.Logger

  import Results._
  def errorResult(reporter: Option[Alerter[F]])(error: abtest.Error): F[Result] = {

    def serverError(msg: String): F[Result] = {
      F.delay(Logger("Thomas").error("Server Error: " + msg)) *>
        reporter.traverse(_.alert(msg)) *>
        F.pure(InternalServerError(errorJson(msg)))
    }

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
      case DuplicatedGroupName       => "group names must be unique."
      case EmptyGroups               => "There must be at least one group."
      case EmptyGroupMeta            => "Group meta to update is empty."
      case GroupNameTooLong          => "Group names must be less than 256 chars."
      case GroupNameDoesNotExist(gn) => s"The group name $gn does not exist in the test."
      case InvalidFeatureName =>
        s"Feature name can only consist of alphanumeric _, - and ."
      case InvalidAlternativeIdName =>
        s"AlternativeIdName can only consist of alphanumeric _, - and ."
      case EmptyUserId => s"User id cannot be an empty string."
    }

    error match {
      case ValidationErrors(detail) =>
        BadRequest(errorJson(detail.toList.map(validationErrorMsg))).pure[F]
      case APINotFound(_)       => F.pure(NotFound)
      case FailedToPersist(msg) => serverError("Failed to save to DB: " + msg)
      case DBException(t)       => serverError("DB Error" + t.getMessage)
      case DBLastError(t)       => serverError("DB Operation Rejected" + t)
      case CannotToChangePastTest(start) =>
        BadRequest(errorJson(s"Cannot change a test that already started at $start"))
          .pure[F]
      case ConflictCreation(fn) =>
        Conflict(errorJson(
          s"There is another test being created right now, could this one be a duplicate? $fn"))
          .pure[F]
      case ConflictTest(existing) =>
        Conflict(
          errorJson(
            s"Cannot start a test on ${existing.data.feature} yet before an existing test") ++
            Json.obj(
              "testInConflict" -> Json.obj(
                "id" -> existing._id.value,
                "name" -> existing.data.name,
                "start" -> existing.data.start,
                "ends" -> existing.data.end
              )
            )
        ).pure[F]
    }
  }

  def badRequest(msgs: String*): F[Result] =
    F.pure(BadRequest(errorJson(msgs)))
}

object AbtestController {

  case class InvalidRequest(msg: String) extends RuntimeException with NoStackTrace
  trait Alerter[F[_]] {
    def alert(msg: String): F[Unit]
  }
}
