/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas

import cats.Functor
import cats.arrow.FunctionK
import cats.data.EitherT
import cats.effect.Async
import com.typesafe.config.Config
import lihua.EntityDAO
import lihua.crypt.CryptTsec
import lihua.mongo.{AsyncEntityDAO, DBError, MongoDB, Query, ShutdownHook}

import scala.concurrent.ExecutionContext
import cats.implicits._
import com.iheart.thomas.model.{Abtest, AbtestExtras, Feature}
import play.api.libs.json.JsObject



package object mongo {
  type APIResult[F[_], T] = EitherT[F, Error, T]

  def toApiResult[F[_]: Functor]: FunctionK[AsyncEntityDAO.Result[F, ?], APIResult[F, ?]] = new FunctionK[AsyncEntityDAO.Result[F, ?], APIResult[F, ?]] {
    override def apply[A](fa: AsyncEntityDAO.Result[F, A]): APIResult[F, A] = {
      fa.leftMap {
        case DBError.NotFound            => Error.NotFound
        case DBError.DBException(e, _)   => Error.DBException(e)
        case DBError.WriteError(details) => Error.FailedToPersist(details.map(d => s"code: ${d.code}, msg: ${d.msg}").toList.mkString("\n"))
      }
    }
  }

  def daos[F[_]: Async](
    implicit
    shutdownHook: ShutdownHook,
    config:       Config,
    ex:           ExecutionContext
  ): F[(EntityDAO[APIResult[F, ?], Abtest, JsObject],
         EntityDAO[APIResult[F, ?], AbtestExtras, JsObject],
         EntityDAO[APIResult[F, ?], Feature, JsObject])] = {
    import net.ceedubs.ficus.Ficus._

    cats.tagless.FunctorK[EntityDAO[?[_], Abtest, Query]]
    def convert[A](e: F[EntityDAO[AsyncEntityDAO.Result[F, ?], A, Query]]): F[EntityDAO[APIResult[F, ?], A, JsObject]] =
      e.map(od => EntityDAO.mapK(od.contramap(Query.fromSelector))(toApiResult[F]))


    MongoDB[F](
      config,
      config.as[Option[String]]("mongoDB.secret").map(new CryptTsec[F](_))
    ).flatMap { implicit m =>
      (convert((new AbtestDAOFactory[F]).create),
      convert((new AbtestExtrasDAOFactory[F]).create),
      convert((new FeatureDAOFactory[F]).create)).tupled
    }
  }
}
