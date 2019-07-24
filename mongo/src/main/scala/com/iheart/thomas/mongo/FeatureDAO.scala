/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart
package thomas
package mongo

import cats.effect.{IO, Async}
import cats.implicits._
import com.iheart.thomas.abtest.model._
import abtest.Formats._
import lihua.mongo.EitherTDAOFactory
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.play.json.collection.JSONCollection

import scala.concurrent.ExecutionContext

class FeatureDAOFactory[F[_]: Async](implicit ec: ExecutionContext) extends EitherTDAOFactory[Feature, F]("abtest", "feature") {
  def ensure(collection: JSONCollection): F[Unit] = {
    implicit val contextShiftIO= IO.contextShift(ec)

    IO.fromFuture(IO(collection.indexesManager.ensure(
      Index(Seq(
        ("name", IndexType.Ascending)
      ), unique = true)
    ).void)).to[F]
  }
}
