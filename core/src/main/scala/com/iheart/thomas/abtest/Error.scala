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

sealed abstract class Error
    extends RuntimeException
    with NoStackTrace
    with Product
    with Serializable

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

  case class DBException(e: Throwable) extends Error
  case class DBLastError(override val getMessage: String) extends Error

  case class CannotToChangePastTest(start: Instant) extends Error {
    override def getMessage = s"Cannot change tests that are already started $start"
  }

  sealed trait ValidationError extends Product with Serializable

  case class InconsistentGroupSizes(sizes: List[GroupSize]) extends ValidationError

  case object InconsistentTimeRange extends ValidationError
  case class ConflictTest(existing: Entity[Abtest]) extends Error
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
