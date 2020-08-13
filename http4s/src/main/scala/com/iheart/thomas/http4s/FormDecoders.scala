package com.iheart.thomas
package http4s

import java.time.{OffsetDateTime, ZonedDateTime}
import java.time.format.DateTimeFormatter

import cats.data.NonEmptyList
import cats.data.Validated.Valid
import cats.implicits._
import com.iheart.thomas.abtest.json.play.Formats._
import com.iheart.thomas.abtest.model.Abtest.Specialization
import com.iheart.thomas.abtest.model.{Abtest, _}
import org.http4s.FormDataDecoder._
import org.http4s.{
  FormDataDecoder,
  ParseFailure,
  QueryParamDecoder,
  QueryParameterValue
}
import play.api.libs.json.{Json, Reads}

import scala.util.Try

object FormDecoders {
  val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

  implicit val offsetDateTimeQueryParamDecoder: QueryParamDecoder[OffsetDateTime] = {
    QueryParamDecoder.fromUnsafeCast(
      qp => ZonedDateTime.parse(qp.value, dateTimeFormatter).toOffsetDateTime
    )("OffsetDateTime")
  }

  implicit def tags(key: String): FormDataDecoder[List[Tag]] =
    FormDataDecoder[List[Tag]] { map =>
      Valid(
        map
          .get(key)
          .flatMap(_.headOption)
          .map(_.split(",").toList.map(_.trim))
          .getOrElse(Nil)
      )
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

  implicit val userMetaCriteriaQueryParamDecoder
      : QueryParamDecoder[UserMetaCriterion.And] =
    jsonEntityQueryParamDecoder

  implicit val groupMetasQueryParamDecoder: QueryParamDecoder[GroupMetas] =
    jsonEntityQueryParamDecoder

  implicit val specializationQueryParamDecoder: QueryParamDecoder[Specialization] =
    jsonEntityQueryParamDecoder

  implicit def groupFormDecoder: FormDataDecoder[Group] =
    (
      field[GroupName]("name"),
      field[GroupSize]("size")
    ).mapN(Group.apply)

  implicit def groupRangeFormDecoder: FormDataDecoder[GroupRange] =
    (
      field[BigDecimal]("start"),
      field[BigDecimal]("end")
    ).mapN(GroupRange.apply)

  implicit def AbtestSpecFormDecoder: FormDataDecoder[AbtestSpec] =
    (
      field[TestName]("name"),
      field[FeatureName]("name"),
      field[String]("author"),
      field[OffsetDateTime]("start"),
      fieldOptional[OffsetDateTime]("end"),
      list[Group]("groups"),
      listOf[Tag]("requiredTags"),
      fieldOptional[MetaFieldName]("alternativeIdName"),
      fieldOptional[UserMetaCriterion.And]("userMetaCriteria"),
      fieldEither[Boolean]("reshuffle").default(false),
      list[GroupRange]("segmentRanges"),
      fieldEither[GroupMetas]("groupMetas").default(Map.empty),
      none[Abtest.Specialization].pure[FormDataDecoder]
    ).mapN(AbtestSpec.apply).sanitized
}
