/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas

import java.time.OffsetDateTime

import cats.Monad
import com.iheart.thomas.model._
import lihua.mongo.{Entity, EntityDAO}
import cats.implicits._
import cats.mtl.implicits._
import com.iheart.thomas.model.Abtest.Status.InProgress
import com.iheart.thomas.persistence.AbtestQuery.byTime

import scala.concurrent.duration.FiniteDuration

trait AssignGroups[F[_]] {
  def assign(query: UserGroupQuery): (OffsetDateTime, F[Map[FeatureName, (GroupName, Entity[Abtest])]])
}

object AssignGroups {
  def fromDB[F[_]: Monad](cacheTtl: FiniteDuration)(
    implicit
    abTestDao:          EntityDAO[F, Abtest],
    featureDao:         EntityDAO[F, Feature],
    eligibilityControl: EligibilityControl[F]
  ): AssignGroups[F] = new DefaultAssignGroups[F](
    ofTime => abTestDao.findCached(byTime(ofTime), cacheTtl),
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

      (ofTime, testsRetriever(ofTime).flatMap(_.traverseFilter { test ⇒
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
