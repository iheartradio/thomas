package com.iheart.thomas
package abtest
package json
package play

import model._
import _root_.play.api.libs.json._
import Json.WithDefaultValues
import com.iheart.thomas.abtest.model.Abtest.Specialization
import com.iheart.thomas.abtest.protocol.UpdateUserMetaCriteriaRequest
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
      ): JsResult[UserMetaCriterion] =
      obj.fields match {
        case Seq(("%regex", JsString(r))) => JsSuccess(RegexMatch(f, r))
        case Seq(("%gt", JsNumber(r)))    => JsSuccess(Greater(f, r.doubleValue))
        case Seq(("%ge", JsNumber(r))) =>
          JsSuccess(GreaterOrEqual(f, r.doubleValue))
        case Seq(("%lt", JsNumber(r))) => JsSuccess(Less(f, r.doubleValue))
        case Seq(("%le", JsNumber(r))) => JsSuccess(LessOrEqual(f, r.doubleValue))
        case Seq(("%in", JsArray(seq))) =>
          seq.toList
            .traverse {
              case JsString(str) => JsSuccess(str)
              case j             => JsError(s"Invalid %in expression in $f: $j")
            }
            .map(s => InMatch(f, s.toSet))
        case Seq(("%versionStart", JsString(start))) =>
          JsSuccess(VersionRange(f, start))

        case Seq(("%versionRange", JsArray(Seq(JsString(start), JsString(end))))) =>
          JsSuccess(VersionRange(f, start, Some(end)))
        case j =>
          JsError(JsPath \ f, s"Invalid JSON $j for user meta criteria for field %f")
      }

    def readSets(jvs: JsValue): JsResult[Set[UserMetaCriterion]] = {

      def readField(field: (String, JsValue)): JsResult[UserMetaCriterion] =
        field match {
          case (f, JsString(s))        => JsSuccess(ExactMatch(f, s))
          case ("%not", obj: JsObject) => readSingleObj(obj).map(Not(_))
          case ("%or", jv)             => readSets(jv).map(Or(_))
          case ("%and", jv)            => readSets(jv).map(And(_))
          case (f, obj: JsObject)      => readFieldValue(f, obj)
          case (f, j) =>
            JsError(s"Invalid JSON $j for user meta criteria at field $f")
        }
      def readSingleObj(job: JsObject): JsResult[UserMetaCriterion] =
        if (job.fields.length != 1)
          JsError(s"Only one field expected. Received ${job.keys.mkString(",")}")
        else readField(job.fields.head)

      def readFields(fields: List[(String, JsValue)]) =
        fields
          .map(readField)
          .sequence
          .map(_.toSet)

      jvs match {
        case jo: JsObject => readFields(jo.fields.toList)
        case ja: JsArray =>
          ja.value.toList
            .flatTraverse {
              case jo: JsObject => JsSuccess(jo.fields.toList)
              case unknown =>
                JsError(s"expecting object in array but received: $unknown")
            }
            .flatMap(readFields)
        case j => JsError(s"Invalid JSON $j for user meta criteria")
      }
    }

    def writeSets(criteria: Set[UserMetaCriterion]): JsArray = {
      def writeField(fieldCriterion: FieldCriterion): (String, JsValue) = {
        val value =
          fieldCriterion match {
            case ExactMatch(_, s) => JsString(s)
            case InMatch(_, ss) =>
              Json.obj(s"%in" -> JsArray(ss.map(JsString(_)).toSeq))
            case RegexMatch(_, s)     => Json.obj(s"%regex" -> JsString(s))
            case Greater(_, v)        => Json.obj(s"%gt" -> JsNumber(v))
            case GreaterOrEqual(_, v) => Json.obj(s"%ge" -> JsNumber(v))
            case Less(_, v)           => Json.obj(s"%lt" -> JsNumber(v))
            case LessOrEqual(_, v)    => Json.obj(s"%le" -> JsNumber(v))
            case VersionRange(_, start, Some(end)) =>
              Json.obj(s"%versionRange" -> Json.arr(start, end))
            case VersionRange(_, start, None) =>
              Json.obj(s"%versionStart" -> start)
          }
        fieldCriterion.field -> value
      }

      def writeCrit(criterion: UserMetaCriterion): (String, JsValue) =
        criterion match {
          case And(crit) =>
            s"%and" -> writeSets(crit)
          case Not(crit) =>
            s"%not" -> JsObject(Seq(writeCrit(crit)))
          case Or(crit) =>
            s"%or" -> writeSets(crit)
          case fc: FieldCriterion => writeField(fc)
        }

      JsArray(
        criteria.toSeq
          .map(writeCrit)
          .map(p => JsObject(Seq(p)))
      )
    }

    Format(
      Reads(readSets(_).map(And(_))),
      Writes(a => writeSets(a.criteria))
    )
  }

  implicit val abtestFormat = j.format[Abtest]
  implicit val abtestSpecFormat = j.format[AbtestSpec]
  implicit val featureFormat = j.format[Feature]

  implicit val jEligibilityControlFilter: Format[EligibilityControlFilter] =
    stringADTFormat(
      EligibilityControlFilter.Off,
      EligibilityControlFilter.On,
      EligibilityControlFilter.All
    )

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

  implicit val formatUpdateUserMetaCriteriaRequest
      : Format[UpdateUserMetaCriteriaRequest] = Json.format

}
