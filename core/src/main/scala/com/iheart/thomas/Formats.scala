package com.iheart.thomas

import model._
import play.api.libs.json.Json.WithDefaultValues
import play.api.libs.json._

import scala.reflect.ClassTag

object Formats extends JavaEnumFormats {


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


trait JavaEnumFormats {

  private def myClassOf[T: ClassTag] = implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]

  implicit def javaEnumWrites[ET <: Enum[ET]]: Writes[ET] = Writes {
    case r: Enum[_] ⇒ JsString(r.name())
  }

  implicit def javaEnumReads[ET <: Enum[ET]: ClassTag]: Reads[ET] = Reads {
    case JsString(name) ⇒
      JsSuccess(Enum.valueOf(myClassOf[ET], name))
    //TODO: improve error
    case _ ⇒ JsError("unrecognized format")
  }
  implicit def javaEnumFormats[ET <: Enum[ET]: ClassTag]: Format[ET] = Format(javaEnumReads[ET], javaEnumWrites[ET])

}
