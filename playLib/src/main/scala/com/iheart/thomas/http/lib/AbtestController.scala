/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package http.lib

import cats.data.EitherT
import cats.effect._
import Error.{NotFound => APINotFound, _}
import AbtestController.Alerter
import play.api.libs.json._
import play.api.mvc._
import cats.implicits._
import cats.effect.implicits._
import com.iheart.thomas.model._

import scala.concurrent.Future
import Formats._
import com.iheart.thomas.analysis.KPIDistribution
import lihua.EntityDAO
import lihua.mongo.JsonFormats._

class AbtestController[F[_]](
  api:        API[EitherT[F, Error, ?]],
  kpiDAO:     EntityDAO[EitherT[F, Error, ?], KPIDistribution, JsObject],
  components: ControllerComponents,
  alerter:    Option[Alerter[F]]
)(
  implicit
  F: Effect[F]
) extends AbstractController(components) {
  val thr = new HttpResults[F](alerter)
  import thr._
  type EF[A] = EitherT[F, Error, A]

  implicit private def toResult[Resp: Writes](apiResult: EF[Resp]): Future[Result] =
    apiResult.value
      .flatMap(_.fold(errorResult(alerter), t => Ok(Json.toJson(t)).pure[F]))
      .toIO.unsafeToFuture

  private def withJsonReq[T: Reads](f: T => Future[Result]) = Action.async[JsValue](parse.tolerantJson) { req =>
    req.body.validate[T].fold(
      errs => badRequest(errs.map(_.toString): _*).toIO.unsafeToFuture(),
      f
    )
  }

  import QueryHelpers._

  def getKPIDistribution(name: String) = Action.async(
    kpiDAO.findOne('name -> name)
  )

  def updateKPIDistribution = withJsonReq { (kpi: KPIDistribution) =>
    kpiDAO.findOne('name -> kpi.name.n)
      .flatMap(e => kpiDAO.update(e.copy(data = kpi)))
      .recoverWith { case Error.NotFound => kpiDAO.insert(kpi) }
  }

  def get(id: TestId) = Action.async(api.getTest(id))

  def getByFeature(feature: FeatureName) = Action.async(api.getTestsByFeature(feature))

  def getAllFeatures = Action.async(api.getAllFeatures)

  def terminate(id: TestId) = Action.async(api.terminate(id))

  def getAllTests(at: Option[Long], endAfter: Option[Long]) = Action.async {
    if (endAfter.isDefined && at.isDefined) {
      Future.successful(BadRequest(errorJson("Cannot specify both at and endFrom")))
    } else toResult {
      endAfter.fold(api.getAllTests(at.map(TimeUtil.toDateTime))) { ea =>
        api.getAllTestsEndAfter(TimeUtil.toDateTime(ea))
      }
    }
  }

  def getAllTestsCached(at: Option[Long]) = Action.async {
    val time = at.map(TimeUtil.toDateTime)
    api.getAllTestsCached(time)
  }

  def create(autoResolveConflict: Boolean): Action[JsValue] = withJsonReq((t: AbtestSpec) => api.create(t, autoResolveConflict))

  val create: Action[JsValue] = create(false)

  val createAuto: Action[JsValue] = create(true)

  val continue: Action[JsValue] = withJsonReq((t: AbtestSpec) => api.continue(t))

  def getGroups(userId: UserId, at: Option[Long], userTags: Option[List[Tag]] = None) = Action.async {
    val time = at.map(TimeUtil.toDateTime)
    api.getGroups(userId, time, userTags.map(_.flatMap(_.split(",").map(_.trim))).getOrElse(Nil))
  }

  def parseEpoch(dateTime: String) = Action {
    TimeUtil.parse(dateTime).map(t => Ok(t.toEpochSecond.toString)).getOrElse(BadRequest("Wrong Format"))
  }

  def addOverride(feature: FeatureName, userId: UserId, groupName: GroupName) = Action.async {
    api.addOverrides(feature, Map(userId -> groupName))
  }

  def setOverrideEligibilityIn(feature: FeatureName, overrideEligibility: Boolean) = Action.async {
    api.setOverrideEligibilityIn(feature, overrideEligibility)
  }

  def addOverrides(feature: FeatureName) = withJsonReq { (overrides: Map[UserId, GroupName]) =>
    api.addOverrides(feature, overrides)
  }

  def addGroupMetas(testId: TestId) = withJsonReq((metas: Map[GroupName, GroupMeta]) => api.addGroupMetas(testId, metas))

  def getGroupMetas(testId: TestId) = Action.async(api.getTestExtras(testId))

  val getGroupsWithMeta = withJsonReq((query: UserGroupQuery) => api.getGroupsWithMeta(query))

  def removeOverride(feature: FeatureName, userId: UserId) = Action.async {
    api.removeOverrides(feature, userId)
  }

  def getOverrides(feature: FeatureName) = Action.async {
    api.getOverrides(feature)
  }

}

class HttpResults[F[_]](alerter: Option[Alerter[F]])(implicit F: Async[F]) {

  def errorJson(msgs: Seq[String]): JsObject =
    Json.obj("errors" -> JsArray(msgs.map(JsString)))

  def errorJson(msg: String): JsObject = errorJson(List(msg))

  import play.api.Logger

  import Results._
  def errorResult(reporter: Option[Alerter[F]])(error: Error): F[Result] = {

    def serverError(msg: String): F[Result] = {
      F.delay(Logger.error("Server Error: " + msg)) *>
        reporter.traverse(_.alert(msg)) *>
        F.pure(InternalServerError(errorJson(msg)))
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
      case ValidationErrors(detail)      => BadRequest(errorJson(detail.toList.map(validationErrorMsg))).pure[F]
      case APINotFound                   => F.pure(NotFound)
      case FailedToPersist(msg)          => serverError("Failed to save to DB: " + msg)
      case DBException(t)                => serverError("DB Error" + t.getMessage)
      case DBLastError(t)                => serverError("DB Operation Rejected" + t)
      case CannotToChangePastTest(start) => BadRequest(errorJson(s"Cannot change a test that already started at $start")).pure[F]
      case ConflictCreation(fn)          => Conflict(errorJson(s"There is another test being created right now, could this one be a duplicate?")).pure[F]
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
      ).pure[F]
    }
  }

  def badRequest(msgs: String*): F[Result] =
    F.pure(BadRequest(errorJson(msgs)))
}

object AbtestController {
  trait Alerter[F[_]] {
    def alert(msg: String): F[Unit]
  }
}
