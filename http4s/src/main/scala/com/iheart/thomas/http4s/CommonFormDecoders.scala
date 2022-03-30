package com.iheart.thomas
package http4s

import java.time.{Instant, OffsetDateTime, ZonedDateTime}
import _root_.play.api.libs.json.{JsObject, Json, Reads}
import cats.data.NonEmptyList
import cats.syntax.all._
import org.http4s.FormDataDecoder._
import org.http4s.{
  FormDataDecoder,
  ParseFailure,
  QueryParamDecoder,
  QueryParameterValue
}
import io.estatico.newtype.Coercible
import io.estatico.newtype.ops._

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

trait CommonQueryParamDecoders {
  implicit val offsetDateTimeQueryParamDecoder: QueryParamDecoder[OffsetDateTime] = {
    QueryParamDecoder.fromUnsafeCast(qp =>
      ZonedDateTime.parse(qp.value, Formatters.dateTimeFormatter).toOffsetDateTime
    )("OffsetDateTime")
  }

  implicit val instantQueryParamDecoder: QueryParamDecoder[Instant] = {
    offsetDateTimeQueryParamDecoder.map(_.toInstant)
  }

  implicit val finiteDurationQueryParamDecoder: QueryParamDecoder[FiniteDuration] = {
    QueryParamDecoder.fromUnsafeCast(qp =>
      Duration(qp.value).asInstanceOf[FiniteDuration]
    )("FiniteDuration")
  }

  implicit val bigDecimalQPD: QueryParamDecoder[BigDecimal] =
    QueryParamDecoder.fromUnsafeCast[BigDecimal](qp =>
      BigDecimal(qp.value.toDouble)
    )("BigDecimal")

  implicit def coercibleQueryParamDecoder[A, B](
      implicit coercible: Coercible[A, B],
      qpd: QueryParamDecoder[A]
    ): QueryParamDecoder[B] = qpd.map(_.coerce)

  def jsonEntityQueryParamDecoder[A](implicit A: Reads[A]): QueryParamDecoder[A] =
    (qp: QueryParameterValue) =>
      Try(Json.parse(qp.value)).toEither
        .leftMap(e => ParseFailure("Invalid Json", e.getMessage))
        .toValidatedNel
        .andThen { json =>
          A.reads(json)
            .asEither
            .leftMap { e =>
              NonEmptyList.fromListUnsafe(e.map { case (path, errors) =>
                ParseFailure(
                  "Json field parse failed",
                  s"$path: ${errors.map(_.message).mkString(";")}"
                )
              }.toList)
            }
            .toValidated
        }

  implicit val jsObjectQueryParamDecoder: QueryParamDecoder[JsObject] =
    jsonEntityQueryParamDecoder

  def mapQueryParamDecoder: QueryParamDecoder[Map[String, String]] =
    jsonEntityQueryParamDecoder[Map[String, String]]
}

trait CommonFormDecoders extends CommonQueryParamDecoders {

  implicit def tuple2[A, B](
      implicit A: QueryParamDecoder[A],
      B: QueryParamDecoder[B]
    ): FormDataDecoder[(A, B)] =
    (
      field[A]("_1"),
      field[B]("_2")
    ).tupled

}

object CommonFormDecoders extends CommonFormDecoders
