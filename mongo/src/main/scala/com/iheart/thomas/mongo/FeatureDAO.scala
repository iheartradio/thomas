/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart
package thomas
package mongo

import cats.effect.Async
import cats.implicits._
import com.iheart.thomas.abtest.model._
import com.iheart.thomas.abtest.json.play.Formats._
import lihua.mongo.EitherTDAOFactory
import reactivemongo.api.bson.collection.BSONCollection
import reactivemongo.api.indexes.IndexType

import scala.concurrent.ExecutionContext

class FeatureDAOFactory[F[_]](implicit ec: ExecutionContext, F: Async[F])
    extends EitherTDAOFactory[Feature, F]("abtest", "feature") {
  def ensure(collection: BSONCollection): F[Unit] = {

    F.fromFuture(
      F.delay(
        collection.indexesManager
          .ensure(
            index(
              Seq(
                ("name", IndexType.Ascending)
              ),
              unique = true
            )
          )
          .void
      )
    )
  }
}
