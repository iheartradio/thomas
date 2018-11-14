package com.iheart.thomas
package mongo

import cats.effect.{Async, IO}
import lihua.mongo.EitherTDAOFactory
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.play.json.collection.JSONCollection
import cats.implicits._
import scala.concurrent.ExecutionContext


class GammaKPIDAOFactory[F[_]: Async](implicit ec: ExecutionContext) extends EitherTDAOFactory[GammaKPI, F]("abtest", "gammaKPI") {
  protected def ensure(collection: JSONCollection): F[Unit] =
    IO.fromFuture(IO(collection.indexesManager.ensure(
      Index(Seq(
        ("name", IndexType.Descending)
      ))
    ).void)).to[F]
}

