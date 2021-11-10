package com.iheart.thomas
package abtest

import java.time.{Instant, OffsetDateTime}

import model._
import lihua.{Entity, EntityDAO}
import _root_.play.api.libs.json.{JsObject, Json, Writes}
import utils.time._
import scala.concurrent.duration.FiniteDuration


object QueryDSL {

  object abtests {

    def byTime(time: OffsetDateTime): JsObject = byTime(time.toInstant, None)

    def byTime(
        time: Instant,
        duration: Option[FiniteDuration]
      ): JsObject =
      Json.obj(
        "start" -> Json.obj("$lte" -> duration.fold(time)(time.plusDuration))
      ) ++ endTimeAfter(time)

    def endTimeAfter(time: Instant): JsObject =
      Json.obj(
        "$or" -> Json.arr(
          Json.obj("end" -> Json.obj("$gt" -> time)),
          Json.obj("end" -> Json.obj("$exists" -> false))
        )
      )
  }

  implicit class ExtendedOps[F[_]](self: EntityDAO[F, Feature, JsObject]) {

    def byName(name: FeatureName): F[Entity[Feature]] = self.findOne(Symbol("name") -> name)
    def byNameOption(name: FeatureName): F[Option[Entity[Feature]]] =
      self.findOneOption(Symbol("name") -> name)
  }

  implicit def fromField1[A: Writes](tp: (Symbol, A)): JsObject =
    Json.obj(tp._1.name -> Json.toJson(tp._2))

  implicit def fromFields2[A: Writes, B: Writes](
      p: ((Symbol, A), (Symbol, B))
    ): JsObject =
    p match {
      case ((s1, a), (s2, b)) => Json.obj(s1.name -> a, s2.name -> b)
    }
}
