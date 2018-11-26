/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart
package thomas
package mongo

import cats.effect.{IO, Async}
import cats.implicits._
import com.iheart.thomas.model._
import lihua.mongo.EitherTDAOFactory
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.play.json.collection.JSONCollection
import Formats._

import scala.concurrent.ExecutionContext

  class AbtestDAOFactory[F[_]: Async](implicit ec: ExecutionContext) extends EitherTDAOFactory[Abtest, F]("abtest", "tests") {
  def ensure(collection: JSONCollection): F[Unit] =
    IO.fromFuture(IO(collection.indexesManager.ensure(
      Index(Seq(
        ("start", IndexType.Descending),
        ("end", IndexType.Descending)
      ))
    ) *> collection.indexesManager.ensure(
        Index(Seq(
          ("feature", IndexType.Ascending)
        ))
      ).void)).to[F]

}


class AbtestExtrasDAOFactory[F[_]: Async] extends EitherTDAOFactory[AbtestExtras, F]("abtest", "testsExtras") {
  protected def ensure(collection: JSONCollection): F[Unit] = Async[F].unit
}
