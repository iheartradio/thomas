/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package abtest
import cats.MonadThrow
import java.time.Instant

import cats.Monad
import cats.implicits._
import com.iheart.thomas.abtest.model._
import henkan.convert.Syntax._
import TimeUtil._

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.control.NoStackTrace
import lihua.Entity

object AssignGroups {

  type PresentTestsData = Vector[(Entity[Abtest], Feature)]

  private def assign[F[_]: Monad](
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
        }
      else if (feature.overrideEligibility)
        overriddenGroup
      else
        None
    }
  }

  def assign[F[_]: EligibilityControl](
      tests: TestsData,
      query: UserGroupQuery,
      consistencyTolerance: FiniteDuration
    )(implicit F: MonadThrow[F],
      nowF: F[Instant]
    ): F[Map[FeatureName, AssignmentResult]] = {

    query.at.map(_.toInstant.pure[F]).getOrElse(nowF).flatMap { targetTime =>
      if (tests.withinTolerance(consistencyTolerance, targetTime)) {
        tests.data
          .traverseFilter {
            case (test, feature) =>
              if (
                test.data.hasEligibilityControl &&
                query.eligibilityControlFilter == EligibilityControlFilter.Off
              )
                F.pure(
                  Option((feature.name, MissingEligibilityInfo: AssignmentResult))
                )
              else
                assign[F](test.data, feature, query).map(
                  _.map(gn =>
                    (
                      feature.name,
                      AssignmentWithMeta(
                        gn,
                        test.data.getGroupMetas.get(gn)
                      ): AssignmentResult
                    )
                  )
                )
          }
          .map(_.toMap)
      } else
        F.raiseError(
          InsufficientTestsDataToAssign(
            (tests.at, tests.duration),
            targetTime,
            consistencyTolerance
          )
        )
    }
  }

  case class InsufficientTestsDataToAssign(
      testDataRange: (Instant, Option[FiniteDuration]),
      targetTime: Instant,
      tolerance: FiniteDuration)
      extends RuntimeException
      with NoStackTrace {
    override val getMessage =
      s"test data ranges starts ${testDataRange._1} to ${testDataRange._2
        .fold("")(_.toString())}, querying time: $targetTime,  tolerance: $tolerance "
  }

  sealed trait AssignmentResult extends Serializable with Product

  case object MissingEligibilityInfo extends AssignmentResult

  case class AssignmentWithMeta(
      groupName: GroupName,
      meta: Option[GroupMeta])
      extends AssignmentResult

  object AssignmentResult {
    val missingInfo = MissingEligibilityInfo
    def withMeta(
        groupName: GroupName,
        m: Option[GroupMeta]
      ) = AssignmentWithMeta(groupName, m)
  }
}

case class TestsData(
    at: Instant,
    data: Vector[(Entity[Abtest], Feature)],
    duration: Option[FiniteDuration]) {

  def withinTolerance(
      tolerance: FiniteDuration,
      target: Instant
    ): Boolean = {

    assert(tolerance >= Duration.Zero, "tolerance cannot be less than zero")
    val cutOffTimeBegin = at.minusNanos(tolerance.toNanos)

    val endTime =
      duration.fold(at)(at.plusDuration)
    val cutOffTimeEnd = endTime.plusNanos(tolerance.toNanos)

    !target.isBefore(cutOffTimeBegin) &&
    !target.isAfter(
      cutOffTimeEnd
    )

  }
}
