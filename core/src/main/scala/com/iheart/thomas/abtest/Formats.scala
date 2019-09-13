package com.iheart.thomas

package abtest
import model._

import _root_.play.api.libs.json.Json
import _root_.play.api.libs.json.Json.WithDefaultValues

object Formats {

  val j = Json.using[WithDefaultValues]

  implicit val groupFormat = j.format[Group]

  implicit val groupRangeFormat = j.format[GroupRange]
  implicit val abtestFormat = j.format[Abtest]
  implicit val abtestSpecFormat = j.format[AbtestSpec]
  implicit val featureFormat = j.format[Feature]
  implicit val userGroupQueryFormat = j.format[UserGroupQuery]
  implicit val userGroupQueryResultFormat = j.format[UserGroupQueryResult]

}
