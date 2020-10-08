package com.iheart.thomas
package http4s

import java.time.{OffsetDateTime, ZonedDateTime}

import _root_.play.api.libs.json.{JsObject, Json, Reads}
import cats.data.NonEmptyList
import cats.implicits._
import org.http4s.FormDataDecoder._
import org.http4s.{
  FormDataDecoder,
  ParseFailure,
  QueryParamDecoder,
  QueryParameterValue
}

import scala.util.Try

trait CommonQueryParamDecoders {
  implicit val offsetDateTimeQueryParamDecoder: QueryParamDecoder[OffsetDateTime] = {
    QueryParamDecoder.fromUnsafeCast(
      qp =>
        ZonedDateTime.parse(qp.value, Formatters.dateTimeFormatter).toOffsetDateTime
    )("OffsetDateTime")
  }

  implicit val bigDecimalQPD: QueryParamDecoder[BigDecimal] =
    QueryParamDecoder.fromUnsafeCast[BigDecimal](
      qp => BigDecimal(qp.value.toDouble)
    )("BigDecimal")

  def jsonEntityQueryParamDecoder[A](implicit A: Reads[A]): QueryParamDecoder[A] =
    (qp: QueryParameterValue) =>
      Try(Json.parse(qp.value)).toEither
        .leftMap(e => ParseFailure("Invalid Json", e.getMessage))
        .toValidatedNel
        .andThen { json =>
          A.reads(json)
            .asEither
            .leftMap { e =>
              NonEmptyList.fromListUnsafe(e.map {
                case (path, errors) =>
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
