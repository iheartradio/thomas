/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package abtest

import cats.Monad
import cats.implicits._
import com.iheart.thomas.abtest.model._
import henkan.convert.Syntax._

object AssignGroups {

  def assign[F[_]: Monad](
      test: Abtest,
      feature: Feature,
      query: UserGroupQuery
    )(implicit eligibilityControl: EligibilityControl[F]
    ): F[Option[GroupName]] = {
    eligibilityControl.eligible(query, test).map { eligible =>
      val idToUse = test.idToUse(query.to[UserInfo]())
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

  def assign[F[_]: Monad](
      tests: Vector[(Abtest, Feature)],
      query: UserGroupQuery
    )(implicit eligibilityControl: EligibilityControl[F]
    ): F[Map[FeatureName, (GroupName, Abtest)]] = {
    tests
      .traverseFilter {
        case (test, feature) =>
          assign[F](test, feature, query).map(
            _.map(gn => (feature.name, (gn, test)))
          )
      }
      .map(_.toMap)

  }

}
