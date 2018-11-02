/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas.mongo

import com.iheart.thomas.model._
import play.api.libs.json.Json.WithDefaultValues
import play.api.libs.json._

object Formats {
  import lihua.mongo.JsonFormats._

  private val j = Json.using[WithDefaultValues]

  implicit val groupFormat = j.format[Group]

  implicit val groupRangeFormat = j.format[GroupRange]
  implicit val abtestFormat = j.format[Abtest]
  implicit val abtestSpecFormat = j.format[AbtestSpec]
  implicit val abtestExtrasFormat = j.format[AbtestExtras]
  implicit val featureFormat = j.format[Feature]
  implicit val userGroupQueryFormat = j.format[UserGroupQuery]
  implicit val userGroupQueryResultFormat = j.format[UserGroupQueryResult]

}
