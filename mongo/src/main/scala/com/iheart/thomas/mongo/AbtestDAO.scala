/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */
package com.iheart
package thomas
package mongo

import cats.effect.Async
import cats.syntax.all._
import com.iheart.thomas.abtest.model._
import lihua.mongo.EitherTDAOFactory
import reactivemongo.api.indexes.IndexType
import com.iheart.thomas.abtest.json.play.Formats._
import reactivemongo.api.bson.collection.BSONCollection

import scala.concurrent.ExecutionContext

class AbtestDAOFactory[F[_]](implicit ec: ExecutionContext, F: Async[F])
    extends EitherTDAOFactory[Abtest, F]("abtest", "tests") {

  def ensure(collection: BSONCollection): F[Unit] = {
    F.fromFuture(
      F.delay(
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
    )

  }
}
