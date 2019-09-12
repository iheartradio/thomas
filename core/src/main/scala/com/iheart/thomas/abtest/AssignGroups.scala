/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package abtest

import java.time.OffsetDateTime

import cats.Monad
import cats.implicits._
import com.iheart.thomas.abtest.model.Abtest.Status.InProgress
import com.iheart.thomas.abtest.model._
import lihua.Entity

trait AssignGroups[F[_]] {
  def assign(query: UserGroupQuery)
    : F[(OffsetDateTime, Map[FeatureName, (GroupName, Entity[Abtest])])]
}

object AssignGroups {

  //todo: replace this using the static assign methods
  def fromTestsFeatures[F[_]: Monad](
      data: Vector[(Entity[Abtest], Feature)]
  )(implicit eligibilityControl: EligibilityControl[F]): AssignGroups[F] =
    new DefaultAssignGroups[F](
      ofTime =>
        data
          .collect {
            case (et @ Entity(_, test), _) if test.statusAsOf(ofTime) == InProgress => et
          }
          .pure[F],
      fn =>
        data
          .collectFirst {
            case (_, f) if f.name == fn => f
          }
          .pure[F]
    )

  class DefaultAssignGroups[F[_]: Monad](
      testsRetriever: OffsetDateTime => F[Vector[Entity[Abtest]]],
      featureRetriever: FeatureName => F[Option[Feature]]
  )(implicit eligibilityControl: EligibilityControl[F])
      extends AssignGroups[F] {

    def assign(query: UserGroupQuery)
      : F[(OffsetDateTime, Map[FeatureName, (GroupName, Entity[Abtest])])] = {
      val ofTime = query.at.getOrElse(TimeUtil.currentMinute)
      val allTests = testsRetriever(ofTime)
      val targetTests =
        if (query.features.isEmpty) allTests
        else allTests.map(_.filter(t => query.features.contains(t.data.feature)))
      targetTests
        .flatMap(_.traverseFilter { test ⇒
          eligibilityControl.eligible(query, test.data).flatMap {
            eligible =>
              featureRetriever(test.data.feature).map {
                feature =>
                  val idToUse = test.data.idToUse(query)
                  def overriddenGroup = {
                    (feature, idToUse).mapN((f, uid) => f.overrides.get(uid)).flatten
                  }
                  {
                    if (eligible)
                      overriddenGroup orElse {
                        idToUse.flatMap(uid => Bucketing.getGroup(uid, test.data))
                      } else if (feature.fold(false)(_.overrideEligibility))
                      overriddenGroup
                    else
                      None
                  }.map(gn => (test.data.feature, (gn, test)))
              }
          }
        }.map(_.toMap))
        .map((ofTime, _))
    }
  }

  def assign[F[_]: Monad](test: Abtest, feature: Feature, query: UserGroupQuery)(
      implicit eligibilityControl: EligibilityControl[F]): F[Option[GroupName]] = {
    eligibilityControl.eligible(query, test).map { eligible =>
      val idToUse = test.idToUse(query)
      def overriddenGroup = {
        idToUse.map(uid => feature.overrides.get(uid)).flatten
      }

      if (eligible)
        overriddenGroup orElse {
          idToUse.flatMap(uid => Bucketing.getGroup(uid, test))
        } else if (feature.overrideEligibility)
        overriddenGroup
      else
        None
    }
  }

  def assign[F[_]: Monad](tests: List[(Abtest, Feature)], query: UserGroupQuery)(
      implicit eligibilityControl: EligibilityControl[F])
    : F[Map[FeatureName, GroupName]] = {
    tests
      .traverseFilter {
        case (test, feature) =>
          assign[F](test, feature, query).map(_.map((feature.name, _)))
      }
      .map(_.toMap)

  }

}
