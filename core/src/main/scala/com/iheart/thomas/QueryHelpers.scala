package com.iheart.thomas

import java.time.OffsetDateTime

import com.iheart.thomas.model.{Feature, FeatureName}
import lihua.{Entity, EntityDAO}
import play.api.libs.json.{JsObject, Json, Writes}

object QueryHelpers {

  object abtests {
    def byTime(time: OffsetDateTime): JsObject =
      Json.obj(
        "start" → Json.obj("$lte" → time)
      ) ++ endTimeAfter(time)

    def endTimeAfter(time: OffsetDateTime): JsObject =
      Json.obj(
        "$or" -> Json.arr(
          Json.obj("end" → Json.obj("$gt" → time)),
          Json.obj("end" → Json.obj("$exists" → false))
        )
      )
  }


  implicit class ExtendedOps[F[_]](self: EntityDAO[F, Feature, JsObject]) {
    def byName(name: FeatureName): F[Entity[Feature]] = self.findOne('name -> name)
  }


  implicit def fromField1[A : Writes](tp: (Symbol, A)): JsObject =
    Json.obj(tp._1.name -> Json.toJson(tp._2))

  implicit def fromFields2[A : Writes, B: Writes](p: ((Symbol, A), (Symbol, B))): JsObject = p match {
    case ((s1, a), (s2, b)) => Json.obj(s1.name -> a, s2.name -> b)
  }
}
