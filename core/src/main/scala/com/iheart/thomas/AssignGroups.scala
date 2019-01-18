/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas

import java.time.OffsetDateTime

import cats.{Monad}
import com.iheart.thomas.model._
import lihua.{Entity, EntityDAO}
import cats.implicits._
import com.iheart.thomas.model.Abtest.Status.InProgress
import play.api.libs.json.JsObject

import scala.concurrent.duration.FiniteDuration

trait AssignGroups[F[_]] {
  def assign(query: UserGroupQuery): (OffsetDateTime, F[Map[FeatureName, (GroupName, Entity[Abtest])]])
}

object AssignGroups {
  import QueryHelpers._
  def fromDB[F[_]: Monad](cacheTtl: FiniteDuration)(
    implicit
    abTestDao:          EntityDAO[F, Abtest, JsObject],
    featureDao:         EntityDAO[F, Feature, JsObject],
    eligibilityControl: EligibilityControl[F]
  ): AssignGroups[F] = new DefaultAssignGroups[F](
    ofTime => abTestDao.findCached(abtests.byTime(ofTime), cacheTtl),
    fn => featureDao.findCached('name -> fn, cacheTtl).map(_.headOption.map(_.data))
  )

  def fromTestsFeatures[F[_]: Monad](
    data: Vector[(Entity[Abtest], Feature)]
  )(implicit eligibilityControl: EligibilityControl[F]): AssignGroups[F] = new DefaultAssignGroups[F](
    ofTime => data.collect {
      case (et @ Entity(_, test), _) if test.statusAsOf(ofTime) == InProgress => et
    }.pure[F],
    fn => data.collectFirst {
      case (_, f) if f.name == fn => f
    }.pure[F]
  )

  class DefaultAssignGroups[F[_]: Monad](
    testsRetriever:   OffsetDateTime => F[Vector[Entity[Abtest]]],
    featureRetriever: FeatureName => F[Option[Feature]]
  )(implicit eligibilityControl: EligibilityControl[F]) extends AssignGroups[F] {

    def assign(query: UserGroupQuery): (OffsetDateTime, F[Map[FeatureName, (GroupName, Entity[Abtest])]]) = {
      val ofTime = query.at.getOrElse(TimeUtil.currentMinute)
      val allTests = testsRetriever(ofTime)
      val targetTests = if(query.features.isEmpty) allTests else allTests.map(_.filter(t => query.features.contains(t.data.feature)))
      (ofTime,
        targetTests.flatMap(_.traverseFilter { test ⇒
          eligibilityControl.eligible(query, test.data).flatMap { eligible =>
            featureRetriever(test.data.feature).map { feature =>
              val idToUse = test.data.idToUse(query)
              def overriddenGroup = {
                (feature, idToUse).mapN((f, uid) => f.overrides.get(uid)).flatten
              }
              {
                if (eligible)
                  overriddenGroup orElse {
                    idToUse.flatMap(uid => Bucketing.getGroup(uid, test.data))
                  }
                else if (feature.fold(false)(_.overrideEligibility))
                  overriddenGroup
                else
                  None
              }.map(gn => (test.data.feature, (gn, test)))
            }
          }
        }.map(_.toMap)))
    }
  }

}
