package lihua.playJson

import lihua.{Entity, EntityId}
import play.api.libs.json.Json.toJson
import play.api.libs.json.{Format, JsObject, JsResult, JsValue, Json, OFormat}



trait Formats {
  implicit object EntityIdFormat extends Format[EntityId] {

    override def reads(json: JsValue): JsResult[EntityId] = (json \ "$oid").validate[String].map(EntityId(_))

    override def writes(o: EntityId): JsValue = Json.obj("$oid" → o.value)
  }


  implicit def entityFormat[T: Format]: OFormat[Entity[T]] = new OFormat[Entity[T]] {
    def writes(e: Entity[T]): JsObject =
      toJson(e.data).as[JsObject] + ("_id" -> toJson(e._id))

    def reads(json: JsValue): JsResult[Entity[T]] = for {
      id <- (json \ "_id").validate[EntityId]
      t <- json.validate[T]
    } yield Entity(id, t)
  }

}

object Formats extends Formats