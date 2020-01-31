package com.iheart.thomas

package abtest
import model._
import _root_.play.api.libs.json._
import Json.WithDefaultValues
import com.iheart.thomas.abtest.model.Abtest.{EligibilityType, Specialization}
import lihua.playJson.Formats._
import _root_.play.api.libs.functional.InvariantFunctorOps

import concurrent.duration._
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
    stringADTFormat(Specialization.MultiArmBanditConversion)

  implicit val jEligibilityType: Format[EligibilityType] =
    stringADTFormat(EligibilityType.Controlled, EligibilityType.AllEligible)

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
    new InvariantFunctorOps(implicitly[Format[Long]]).inmap(_.nanos, _.toNanos)

  implicit val testsDataFormat = j.format[TestsData]

}
