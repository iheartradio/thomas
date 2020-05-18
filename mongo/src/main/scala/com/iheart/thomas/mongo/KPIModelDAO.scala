package com.iheart.thomas
package mongo

import cats.effect.{Async, IO}
import lihua.mongo.EitherTDAOFactory
import reactivemongo.api.indexes.IndexType
import reactivemongo.play.json.collection.JSONCollection
import cats.implicits._
import com.iheart.thomas.analysis.KPIModel

import scala.concurrent.ExecutionContext

class KPIModelDAOFactory[F[_]: Async](implicit ec: ExecutionContext)
    extends EitherTDAOFactory[KPIModel, F]("abtest", "KPIModels") {
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
