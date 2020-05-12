/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package play
import java.time.{Instant, ZoneId, ZoneOffset}

import abtest._
import com.iheart.thomas.abtest.json.play.Formats._
import model._
import cats.effect._
import ThomasController.{Alerter, InvalidRequest}
import _root_.play.api.libs.json._
import _root_.play.api.mvc._
import cats.implicits._
import com.iheart.thomas.analysis.{KPIDistribution, KPIDistributionApi}
import com.iheart.thomas.abtest.protocol.UpdateUserMetaCriteriaRequest
import lihua.{Entity, EntityId}
import lihua.mongo.JsonFormats._

class AbtestController[F[_]](
    api: AbtestAlg[F],
    kpiAPI: KPIDistributionApi[F],
    components: ControllerComponents,
    alerter: Option[Alerter[F]]
  )(implicit
    F: Effect[F])
    extends ThomasController(components, alerter) {

  def getKPIDistribution(name: String) = action(
    liftOption(kpiAPI.get(name), s"Cannot find KPI named $name")
  )

  val updateKPIDistribution = jsonAction { (kpi: KPIDistribution) =>
    kpiAPI.upsert(kpi)
  }

  def get(id: String) = action(api.getTest(EntityId(id)))

  def getByFeature(feature: FeatureName) =
    action(api.getTestsByFeature(feature))

  def getAllFeatures = action(api.getAllFeatures)

  def terminate(id: String) = action(api.terminate(EntityId(id)))

  def getAllTests(
      at: Option[Long],
      endAfter: Option[Long]
    ) = action {
    if (endAfter.isDefined && at.isDefined) {
      F.raiseError[Vector[Entity[Abtest]]](
        InvalidRequest("Cannot specify both at and endAfter")
      )
    } else
      endAfter.fold(api.getAllTests(at.map(TimeUtil.toDateTime))) { ea =>
        api.getAllTestsEndAfter(ea)
      }

  }

  def getAllTestsCached(at: Option[Long]) = action {
    api.getAllTestsCachedEpoch(at)
  }

  def getTestsData(
      atEpochMilli: Long,
      durationMillisecond: Option[Long]
    ) = action {
    import scala.concurrent.duration._
    api.getTestsData(
      Instant.ofEpochMilli(atEpochMilli),
      durationMillisecond.map(_.millis)
    )
  }

  def create(autoResolveConflict: Boolean): Action[JsValue] =
    jsonAction((t: AbtestSpec) => api.create(t, autoResolveConflict))

  val create: Action[JsValue] = create(false)

  val createAuto: Action[JsValue] = create(true)

  val continue: Action[JsValue] = jsonAction(
    (t: AbtestSpec) => api.continue(t)
  )

  def getGroups(
      userId: UserId,
      at: Option[Long],
      userTags: Option[List[Tag]] = None
    ) =
    action {
      val time = at.map(TimeUtil.toDateTime)
      api.getGroups(
        userId,
        time,
        userTags.map(_.flatMap(_.split(",").map(_.trim))).getOrElse(Nil)
      )
    }

  def parseEpoch(dateTime: String) = Action {
    val sysOffset: ZoneOffset =
      ZoneId.systemDefault().getRules.getOffset(Instant.now())

    TimeUtil
      .parse(dateTime, sysOffset)
      .map(t => Ok(t.toEpochSecond.toString))
      .getOrElse(BadRequest("Wrong Format"))
  }

  def addOverride(
      feature: FeatureName,
      userId: UserId,
      groupName: GroupName
    ) =
    action {
      api.addOverrides(feature, Map(userId -> groupName))
    }

  def setOverrideEligibilityIn(
      feature: FeatureName,
      overrideEligibility: Boolean
    ) =
    action {
      api.setOverrideEligibilityIn(feature, overrideEligibility)
    }

  def addOverrides(feature: FeatureName) = jsonAction {
    (overrides: Map[UserId, GroupName]) =>
      api.addOverrides(feature, overrides)
  }

  def addGroupMetas(
      testId: String,
      auto: Boolean
    ) =
    jsonAction(
      (metas: Map[GroupName, GroupMeta]) =>
        api.addGroupMetas(EntityId(testId), metas, auto)
    )

  def updateUserMetaCriteria(testId: String) =
    jsonAction { (r: UpdateUserMetaCriteriaRequest) =>
      api.updateUserMetaCriteria(EntityId(testId), r.criteria, r.auto)
    }

  def removeGroupMetas(
      testId: String,
      auto: Boolean
    ) = action {
    api.removeGroupMetas(EntityId(testId), auto)
  }

  //for legacy support
  def getGroupMetas(testId: String) = action {
    api.getTest(EntityId(testId)).map(_.data.groupMetas)
  }

  val getGroupsWithMeta = jsonAction(
    (query: UserGroupQuery) => api.getGroupsWithMeta(query)
  )

  def removeOverride(
      feature: FeatureName,
      userId: UserId
    ) = action {
    api.removeOverrides(feature, userId)
  }

  def removeAllOverrides(feature: FeatureName) = action {
    api.removeAllOverrides(feature)
  }

  def getOverrides(feature: FeatureName) = action {
    api.getOverrides(feature)
  }

}
