/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart
package abtest
package persistence

import java.time.OffsetDateTime

import cats.effect.IO
import cats.implicits._
import com.iheart.abtest.model._
import lihua.mongo.IOEitherTDAOFactory
import play.api.libs.json.{JsObject, Json}
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.play.json._
import reactivemongo.play.json.collection.JSONCollection
import Formats._
import scala.concurrent.ExecutionContext

class AbtestDAOFactory(implicit ec: ExecutionContext) extends IOEitherTDAOFactory[Abtest]("abtest", "tests") {
  def ensure(collection: JSONCollection): IO[Unit] =
    IO.fromFuture(IO(collection.indexesManager.ensure(
      Index(Seq(
        ("start", IndexType.Descending),
        ("end", IndexType.Descending)
      ))
    ) *> collection.indexesManager.ensure(
        Index(Seq(
          ("feature", IndexType.Ascending)
        ))
      ).void))

}

object AbtestQuery {
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

class AbtestExtrasDAOFactory extends IOEitherTDAOFactory[AbtestExtras]("abtest", "testsExtras") {
  protected def ensure(collection: JSONCollection): IO[Unit] = IO.unit
}
