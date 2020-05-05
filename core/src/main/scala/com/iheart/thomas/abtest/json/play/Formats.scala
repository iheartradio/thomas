package com.iheart.thomas
package abtest
package json
package play

import model._
import _root_.play.api.libs.json._
import Json.WithDefaultValues
import com.iheart.thomas.abtest.model.Abtest.Specialization
import com.iheart.thomas.abtest.model.UserMetaCriterion.ExactMatch
import lihua.playJson.Formats._

import concurrent.duration._
import scala.util.Try

object Formats {
  def stringADTFormat[T](items: T*): Format[T] =
    new Format[T] {
      val map = items.map(i => (i.toString, i)).toMap
      def reads(json: JsValue): JsResult[T] =
        json match {
          case JsString(s) if map.contains(s) => JsSuccess(map(s))

          case _ => JsError("Unrecognized Value")
        }

      def writes(o: T): JsValue =
        JsString(o.toString)
    }

  implicit val jSpecialization: Format[Specialization] =
    stringADTFormat(Specialization.MultiArmBandit)

  val j = Json.using[WithDefaultValues]

  implicit val groupFormat = j.format[Group]

  implicit val groupRangeFormat = j.format[GroupRange]

  import com.iheart.thomas.abtest.model.UserMetaCriterion._
  import cats.implicits._
  import Instances._
  implicit val userMetaCriteriaFormat: Format[UserMetaCriterion.And] = {
    def readFieldValue(
        f: MetaFieldName,
        obj: JsObject
      ): JsResult[UserMetaCriterion] = obj.fields match {
      case Seq(("$regex", JsString(r))) => JsSuccess(RegexMatch(f, r))
      case Seq(("$in", JsArray(seq))) =>
        seq.toList
          .traverse {
            case JsString(str) => JsSuccess(str)
            case j             => JsError(s"Invalid $$in expression in $f: $j")
          }
          .map(s => InMatch(f, s.toSet))
      case Seq(("$versionStart", JsString(start))) =>
        JsSuccess(VersionRange(f, start))

      case Seq(("$versionRange", JsArray(Seq(JsString(start), JsString(end))))) =>
        JsSuccess(VersionRange(f, start, Some(end)))
      case j => JsError(s"Invalid JSON $j for user meta criteria for field $f")
    }

    def readSets(jo: JsObject): JsResult[Set[UserMetaCriterion]] = {
      jo.fields.toList
        .map {
          case (f, JsString(s))        => JsSuccess(ExactMatch(f, s))
          case ("$or", obj: JsObject)  => readSets(obj).map(Or(_))
          case ("$and", obj: JsObject) => readSets(obj).map(And(_))
          case (f, obj: JsObject)      => readFieldValue(f, obj)
          case (f, j) =>
            JsError(s"Invalid JSON $j for user meta criteria at field $f")
        }
        .sequence
        .map(_.toSet)
    }

    def writeSets(criteria: Set[UserMetaCriterion]): JsObject = JsObject(
      criteria.toSeq.map {
        case ExactMatch(f, s) => f -> JsString(s)
        case InMatch(f, ss) =>
          f -> Json.obj(s"$$in" -> JsArray(ss.map(JsString(_)).toSeq))
        case RegexMatch(f, s) => f -> Json.obj(s"$$regex" -> JsString(s))
        case And(crit) =>
          s"$$and" -> writeSets(crit)
        case Or(crit) =>
          s"$$or" -> writeSets(crit)
        case VersionRange(f, start, Some(end)) =>
          f -> Json.obj(s"$$versionRange" -> Json.arr(start, end))
        case VersionRange(f, start, None) =>
          f -> Json.obj(s"$$versionStart" -> start)
      }
    )

    Format(
      Reads {
        case jo: JsObject =>
          readSets(jo).map(And(_))
        case j => JsError(s"Invalid JSON $j for user meta criteria")

      },
      Writes(a => writeSets(a.criteria))
    )
  }

  implicit val abtestFormat = j.format[Abtest]
  implicit val abtestSpecFormat = j.format[AbtestSpec]
  implicit val featureFormat = j.format[Feature]
  implicit val userGroupQueryFormat =
    j.format[UserGroupQuery]
  implicit val userGroupQueryResultFormat =
    j.format[UserGroupQueryResult]

  implicit val finiteDurationFormat: Format[FiniteDuration] =
    Format[FiniteDuration](
      Reads {
        case JsString(ds) =>
          Try(Duration(ds).asInstanceOf[FiniteDuration])
            .fold(_ => JsError(s"Invalid duration string $ds"), JsSuccess(_))
        case JsNumber(ns) => JsSuccess(ns.toLong.nanos)
        case j            => JsError(s"Invalid json for duration $j")
      },
      Writes(d => JsString(d.toString))
    )

  implicit val testsDataFormat = j.format[TestsData]

}
