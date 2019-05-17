package com.iheart.thomas

import model._
import _root_.play.api.libs.json.Json.WithDefaultValues
import _root_.play.api.libs.json._

import scala.reflect.ClassTag

object Formats {

  val j = Json.using[WithDefaultValues]

  implicit val groupFormat = j.format[Group]

  implicit val groupRangeFormat = j.format[GroupRange]
  implicit val abtestFormat = j.format[Abtest]
  implicit val abtestSpecFormat = j.format[AbtestSpec]
  implicit val abtestExtrasFormat = j.format[AbtestExtras]
  implicit val featureFormat = j.format[Feature]
  implicit val userGroupQueryFormat = j.format[UserGroupQuery]
  implicit val userGroupQueryResultFormat = j.format[UserGroupQueryResult]

}
