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
import lihua.mongo.EitherTDAOFactory
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.play.json.collection.JSONCollection
import abtest.Formats._

import scala.concurrent.ExecutionContext

class AbtestDAOFactory[F[_]: Async](implicit ec: ExecutionContext)
    extends EitherTDAOFactory[Abtest, F]("abtest", "tests") {

  def ensure(collection: JSONCollection): F[Unit] = {
    implicit val contextShiftIO = IO.contextShift(ec)
    IO.fromFuture(
        IO(
          collection.indexesManager.ensure(
            Index(
              Seq(
                ("start", IndexType.Descending),
                ("end", IndexType.Descending)
              ))
          ) *> collection.indexesManager
            .ensure(Index(Seq(
              ("feature", IndexType.Ascending)
            )))
            .void))
      .to[F]

  }
}
