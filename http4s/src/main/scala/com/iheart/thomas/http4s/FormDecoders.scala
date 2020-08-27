package com.iheart.thomas
package http4s

import java.time.{Instant, OffsetDateTime, ZonedDateTime}

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
import play.api.libs.json.{JsObject, Json, Reads}

import scala.util.Try

object FormDecoders {

  implicit val offsetDateTimeQueryParamDecoder: QueryParamDecoder[OffsetDateTime] = {
    QueryParamDecoder.fromUnsafeCast(
      qp =>
        ZonedDateTime.parse(qp.value, Formatters.dateTimeFormatter).toOffsetDateTime
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

  implicit def tuple2[A, B](
      implicit A: QueryParamDecoder[A],
      B: QueryParamDecoder[B]
    ): FormDataDecoder[(A, B)] =
    (
      field[A]("_1"),
      field[B]("_2")
    ).tupled

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

  implicit val specializationQueryParamDecoder: QueryParamDecoder[Specialization] =
    jsonEntityQueryParamDecoder

  implicit val jsObjectQueryParamDecoder: QueryParamDecoder[JsObject] =
    jsonEntityQueryParamDecoder

  implicit val groupFormDecoder: FormDataDecoder[Group] =
    (
      field[GroupName]("name"),
      field[GroupSize]("size"),
      fieldOptional[JsObject]("meta")
    ).mapN(Group.apply)

  implicit val groupRangeFormDecoder: FormDataDecoder[GroupRange] =
    (
      field[BigDecimal]("start"),
      field[BigDecimal]("end")
    ).mapN(GroupRange.apply)

  implicit val AbtestSpecFormDecoder: FormDataDecoder[AbtestSpec] =
    (
      field[TestName]("name"),
      field[FeatureName]("feature"),
      field[String]("author"),
      field[OffsetDateTime]("start"),
      fieldOptional[OffsetDateTime]("end"),
      list[Group]("groups"),
      tags("requiredTags"),
      fieldOptional[MetaFieldName]("alternativeIdName"),
      fieldOptional[UserMetaCriterion.And]("userMetaCriteria"),
      fieldEither[Boolean]("reshuffle").default(false),
      list[GroupRange]("segmentRanges"),
      none[Abtest.Specialization].pure[FormDataDecoder],
      Map.empty[GroupName, GroupMeta].pure[FormDataDecoder]
    ).mapN(AbtestSpec.apply).sanitized

  implicit val FeatureFormDecoder: FormDataDecoder[Feature] =
    (
      field[FeatureName]("name"),
      fieldOptional[String]("description"),
      list[(String, String)]("overrides").map(_.toMap),
      fieldEither[Boolean]("overrideEligibility").default(false),
      none[Instant].pure[FormDataDecoder]
    ).mapN(Feature.apply)
}
