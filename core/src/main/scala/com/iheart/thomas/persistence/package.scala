/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas

import cats.Functor
import cats.arrow.FunctionK
import cats.data.EitherT
import cats.effect.IO
import lihua.mongo.DBError
import lihua.mongo.SyncEntityDAO

package object persistence {
  type APIResult[F[_], T] = EitherT[F, Error, T]
  type APIIOResult[T] = APIResult[IO, T]

  def toApiResult[F[_]: Functor]: FunctionK[SyncEntityDAO.Result[F, ?], APIResult[F, ?]] = new FunctionK[SyncEntityDAO.Result[F, ?], APIResult[F, ?]] {
    override def apply[A](fa: SyncEntityDAO.Result[F, A]): APIResult[F, A] = {
      fa.leftMap {
        case DBError.NotFound            => Error.NotFound
        case DBError.DBException(e, _)   => Error.DBException(e)
        case DBError.WriteError(details) => Error.FailedToPersist(details.map(d => s"code: ${d.code}, msg: ${d.msg}").toList.mkString("\n"))
      }
    }
  }
}
