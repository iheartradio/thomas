/*
* Copyright [2017] [iHeartMedia Inc]
* All rights reserved
*/
package lihua
package mongo

import cats.Invariant
import org.joda.time.DateTime
import play.api.libs.json._

import scala.reflect.ClassTag

object JsonFormats extends playJson.Formats {

  implicit val invariantFormat: Invariant[Format] = new Invariant[Format] {
    def imap[A, B](fa: Format[A])(f: A => B)(g: B => A): Format[B] = new Format[B] {
      override def reads(json: JsValue): JsResult[B] = fa.reads(json).map(f)
      override def writes(o: B): JsValue = fa.writes(g(o))
    }
  }


  implicit object JodaFormat extends Format[DateTime] {

    override def reads(json: JsValue): JsResult[DateTime] = (json \ "$date").validate[Long].map(new DateTime(_))

    override def writes(o: DateTime): JsValue = Json.obj("$date" → o.getMillis)
  }

  object StringBooleanFormat extends Format[Boolean] {

    override def reads(json: JsValue): JsResult[Boolean] = json.validate[String].map(_.toLowerCase == "true")

    override def writes(o: Boolean): JsValue = JsString(o.toString)
  }

  object IntBooleanFormat extends Format[Boolean] {

    override def reads(json: JsValue): JsResult[Boolean] = json.validate[Int].map(_ != 0)

    override def writes(o: Boolean): JsValue = JsNumber(if (o) 1 else 0)
  }

  implicit class JsPathMongoDBOps(val self: JsPath) extends AnyVal {
    def formatEntityId = OFormat[String](self.read[EntityId].map(_.value), OWrites[String] { s ⇒ self.write[EntityId].writes(EntityId(s)) })
  }

  implicit def mapFormat[KT: StringParser, VT: Format]: Format[Map[KT, VT]] = new Format[Map[KT, VT]] {
    def writes(o: Map[KT, VT]): JsValue =
      JsObject(o.toSeq.map { case (k, v) ⇒ (k.toString, Json.toJson(v)) })

    def reads(json: JsValue): JsResult[Map[KT, VT]] =
      json.validate[JsObject].map(_.fields.map {
        case (ks, vValue) ⇒ (implicitly[StringParser[KT]].parse(ks), vValue.as[VT])
      }.toMap)
  }

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

  trait StringParser[T] {
    def parse(s: String): T
  }

  object StringParser {
    implicit val intStringParser: StringParser[Int] = new StringParser[Int] {
      def parse(s: String): Int = java.lang.Integer.parseInt(s)
    }

    implicit val stringStringParser: StringParser[String] = new StringParser[String] {
      def parse(s: String): String = s
    }
  }

}
