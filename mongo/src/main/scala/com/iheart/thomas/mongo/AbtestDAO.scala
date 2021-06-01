/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */
package com.iheart
package thomas
package mongo

import cats.effect.{Async, IO}
import cats.implicits._
import com.iheart.thomas.abtest.model._
import lihua.mongo.EitherTDAOFactory
import reactivemongo.api.indexes.IndexType
import com.iheart.thomas.abtest.json.play.Formats._
import reactivemongo.api.bson.collection.BSONCollection

import scala.concurrent.ExecutionContext

class AbtestDAOFactory[F[_]: Async](implicit ec: ExecutionContext)
    extends EitherTDAOFactory[Abtest, F]("abtest", "tests") {

  def ensure(collection: BSONCollection): F[Unit] = {
    implicit val contextShiftIO = IO.contextShift(ec)
    IO.fromFuture(
      IO(
        collection.indexesManager.ensure(
          index(
            Seq(
              ("start", IndexType.Descending),
              ("end", IndexType.Descending)
            )
          )
        ) *> collection.indexesManager
          .ensure(
            index(
              Seq(
                ("feature", IndexType.Ascending)
              )
            )
          )
          .void
      )
    ).to[F]

  }
}
