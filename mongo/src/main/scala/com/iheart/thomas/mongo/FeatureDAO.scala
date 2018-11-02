/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart
package thomas
package mongo

import cats.effect.IO
import cats.implicits._
import com.iheart.thomas.model._
import com.iheart.thomas.persistence.Formats._
import lihua.mongo.{Entity, EntityDAO, IOEitherTDAOFactory}
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.play.json.collection.JSONCollection

import scala.concurrent.ExecutionContext

class FeatureDAOFactory(implicit ec: ExecutionContext) extends IOEitherTDAOFactory[Feature]("abtest", "feature") {
  def ensure(collection: JSONCollection): IO[Unit] =
    IO.fromFuture(IO(collection.indexesManager.ensure(
      Index(Seq(
        ("name", IndexType.Ascending)
      ), unique = true)
    ).void))

}

object FeatureDAOEnhancement {
  implicit class ExtendedOps[F[_]](self: EntityDAO[F, Feature]) {
    def byName(name: FeatureName): F[Entity[Feature]] = self.findOne('name -> name)
  }
}
