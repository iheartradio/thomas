package com.iheart.thomas

package abtest
import model._
import _root_.play.api.libs.json._
import Json.WithDefaultValues
import com.iheart.thomas.abtest.model.Abtest.Specialization
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
