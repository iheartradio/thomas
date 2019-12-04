package com.iheart.thomas
package mongo

import cats.effect.{Async, IO}
import lihua.mongo.EitherTDAOFactory
import reactivemongo.api.indexes.IndexType
import reactivemongo.play.json.collection.JSONCollection
import cats.implicits._
import com.iheart.thomas.analysis.KPIDistribution

import scala.concurrent.ExecutionContext

class KPIDistributionDAOFactory[F[_]: Async](implicit ec: ExecutionContext)
    extends EitherTDAOFactory[KPIDistribution, F]("abtest", "KPIDistributions") {
  protected def ensure(collection: JSONCollection): F[Unit] = {
    implicit def contextShiftIO = IO.contextShift(ec)
    IO.fromFuture(
        IO(
          collection.indexesManager
            .ensure(
              index(
                Seq(
                  ("name", IndexType.Descending)
                )
              )
            )
            .void
        )
      )
      .to[F]
  }
}
