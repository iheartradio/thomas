package com.iheart.thomas.abtest.model

import scala.util.Try
import scala.util.matching.Regex

sealed trait UserMetaCriterion extends Serializable with Product {
  def eligible(userMeta: UserMeta): Boolean
}

object UserMetaCriterion {

  sealed private[abtest] abstract class FieldCriterion extends UserMetaCriterion {
    def field: MetaFieldName

    protected def valueEligible(value: Option[String]): Boolean

    def eligible(userMeta: UserMeta): Boolean = valueEligible(userMeta.get(field))
  }

  case class RegexMatch(
      field: MetaFieldName,
      r: String)
      extends FieldCriterion {
    protected def valueEligible(value: Option[String]): Boolean =
      value.fold(false)(v => new Regex(r).findFirstMatchIn(v).isDefined)
  }

  sealed abstract class NumCompare extends FieldCriterion {
    protected def valueEligible(value: Option[String]): Boolean =
      value.flatMap(v => Try(v.toDouble).toOption).fold(false) { v =>
        this match {
          case Greater(_, t)        => v > t
          case GreaterOrEqual(_, t) => v >= t
          case Less(_, t)           => v < t
          case LessOrEqual(_, t)    => v <= t
        }
      }
  }

  case class Greater(
      field: MetaFieldName,
      threshold: Double)
      extends NumCompare

  case class GreaterOrEqual(
      field: MetaFieldName,
      threshold: Double)
      extends NumCompare

  case class Less(
      field: MetaFieldName,
      threshold: Double)
      extends NumCompare

  case class LessOrEqual(
      field: MetaFieldName,
      threshold: Double)
      extends NumCompare

  case class VersionRange(
      field: MetaFieldName,
      start: String,
      end: Option[String] = None)
      extends FieldCriterion {
    protected def valueEligible(value: Option[String]): Boolean =
      value.fold(false) { v =>
        VersionRange.equalOrAfter(start, v) && end.fold(true)(
          VersionRange.equalOrAfter(v, _)
        )
      }
  }

  object VersionRange {

    private[abtest] def equalOrAfter(
        ver1: String,
        ver2: String
      ): Boolean =
      ver1
        .split("\\.|\\-")
        .zipAll(ver2.split("\\.|\\-"), "0", "0")
        .find { case (a, b) => a != b }
        .fold(true) { case (a, b) => Try(b.toInt >= a.toInt).getOrElse(true) }
  }

  case class ExactMatch(
      field: MetaFieldName,
      s: String)
      extends FieldCriterion {
    protected def valueEligible(value: Option[String]): Boolean =
      value.fold(false)(_ == s)
  }

  case class InMatch(
      field: MetaFieldName,
      matches: Set[String])
      extends FieldCriterion {
    protected def valueEligible(value: Option[String]): Boolean =
      value.fold(false)(matches.contains)
  }

  case class Or(criteria: Set[UserMetaCriterion]) extends UserMetaCriterion {
    def eligible(userMeta: UserMeta): Boolean =
      criteria.exists(_.eligible(userMeta))
  }

  case class Not(inner: UserMetaCriterion) extends UserMetaCriterion {
    def eligible(userMeta: UserMeta): Boolean = !inner.eligible(userMeta)
  }

  case class And(criteria: Set[UserMetaCriterion]) extends UserMetaCriterion {

    def eligible(userMeta: UserMeta): Boolean =
      criteria.forall(_.eligible(userMeta))

    def apply(field: MetaFieldName): Option[UserMetaCriterion] =
      criteria.collectFirst {
        case f: FieldCriterion if f.field == field => f
      }
  }

  def and(criteria: UserMetaCriterion*) = And(criteria.toSet)
  def or(criteria: UserMetaCriterion*) = Or(criteria.toSet)

  def in(
      f: MetaFieldName,
      values: String*
    ) = InMatch(f, values.toSet)

}
