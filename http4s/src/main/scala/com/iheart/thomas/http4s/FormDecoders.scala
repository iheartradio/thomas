package com.iheart.thomas
package http4s

import java.time.OffsetDateTime

import cats.data.NonEmptyList
import cats.implicits._
import com.iheart.thomas.abtest.json.play.Formats._
import com.iheart.thomas.abtest.model.Abtest.Specialization
import com.iheart.thomas.abtest.model._
import org.http4s.server.middleware.FormDataDecoder
import org.http4s.server.middleware.FormDataDecoder._
import org.http4s.{ParseFailure, QueryParamDecoder, QueryParameterValue}
import play.api.libs.json.{Json, Reads}
object FormDecoders {

  implicit val offsetDateTimeQueryParamDecoder: QueryParamDecoder[OffsetDateTime] =
    QueryParamDecoder.fromUnsafeCast(
      qp => OffsetDateTime.parse(qp.value)
    )("OffsetDateTime")

  implicit val bigDecimalQPD: QueryParamDecoder[BigDecimal] =
    QueryParamDecoder.fromUnsafeCast[BigDecimal](
      qp => BigDecimal(qp.value.toDouble)
    )("BigDecimal")

  def jsonEntityQueryParamDecoder[A](implicit A: Reads[A]): QueryParamDecoder[A] =
    (qp: QueryParameterValue) =>
      A.reads(Json.parse(qp.value))
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
      field[Boolean]("reshuffle"),
      list[GroupRange]("segmentRanges"),
      field[GroupMetas]("groupMetas"),
      fieldOptional[Abtest.Specialization]("specialization")
    ).mapN(AbtestSpec.apply)
}
