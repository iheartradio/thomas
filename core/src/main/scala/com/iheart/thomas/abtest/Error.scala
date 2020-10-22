/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package abtest

import java.time.Instant

import cats.data.NonEmptyList
import model._
import lihua.Entity

import scala.util.control.NoStackTrace

sealed abstract class Error(cause: Throwable = null)
    extends RuntimeException(cause)
    with NoStackTrace
    with Product
    with Serializable {}

object Error {

  case class FailedToPersist(msg: String) extends Error {
    override def getMessage: String = msg
  }

  case class ValidationErrors(detail: NonEmptyList[ValidationError]) extends Error {
    override def getMessage: String =
      s"""
         |Validation Errors:
         |$detail
         |""".stripMargin
  }

  case class NotFound(override val getMessage: String) extends Error

  case class DBException(
      cause: Throwable,
      extraMessage: String)
      extends Error(cause) {
    override def getMessage: MetaFieldName = cause.getMessage + " " + extraMessage
  }

  case class DBLastError(override val getMessage: String) extends Error

  case class CannotChangePastTest(start: Instant) extends Error {
    override def getMessage = s"Cannot change tests that are already started $start"
  }

  case class CannotChangeGroupSizeWithFollowUpTest(test: Entity[Abtest])
      extends Error {
    override def getMessage =
      s"Cannot change group sizes due to a follow up test (${test._id})"
  }

  case class CannotUpdateExpiredTest(expired: Instant) extends Error {
    override def getMessage =
      s"Cannot auto create new test from expired test (expired $expired)"
  }

  sealed trait ValidationError extends Product with Serializable

  case class InconsistentGroupSizes(sizes: List[GroupSize]) extends ValidationError

  case object InconsistentTimeRange extends ValidationError
  case object FeatureCannotBeChanged extends Error
  case class ConflictTest(existing: Entity[Abtest]) extends Error {
    override def getMessage =
      s"Cannot schedule to overlap with an existing test (${existing._id} ${existing.data.end
        .map(e => s"ending $e")})"
  }
  case class ConflictCreation(
      feature: FeatureName,
      cause: String)
      extends Error
  case class FailedToReleaseLock(cause: String) extends Error
  case object CannotScheduleTestBeforeNow extends ValidationError
  case object DuplicatedGroupName extends ValidationError
  case object GroupNameTooLong extends ValidationError
  case object EmptyUserId extends ValidationError
  case object EmptyGroupMeta extends ValidationError
  case object InvalidFeatureName extends ValidationError
  case object InvalidAlternativeIdName extends ValidationError
  case class GroupNameDoesNotExist(name: GroupName) extends ValidationError
  case class ContinuationGap(
      lastEnd: Instant,
      scheduledStart: Instant)
      extends ValidationError
  case class ContinuationBefore(
      lasStart: Instant,
      scheduledStart: Instant)
      extends ValidationError

  case object EmptyGroups extends ValidationError
}
