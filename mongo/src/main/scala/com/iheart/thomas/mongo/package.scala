/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas

import cats.{Functor, MonadError}
import cats.arrow.FunctionK
import cats.effect.{Async, Resource}
import com.typesafe.config.Config
import lihua.{EntityDAO, EntityId}
import lihua.crypt.CryptTsec
import lihua.mongo._

import scala.concurrent.ExecutionContext
import cats.implicits._
import cats.tagless.FunctorK
import com.iheart.thomas.analysis.KPIDistribution
import com.iheart.thomas.abtest.model.{Abtest, Feature}
import lihua.mongo.DBError.UpdatedCountErrorDetail
import _root_.play.api.libs.json.JsObject
import abtest._
import lihua.mongo.AsyncEntityDAO.Result

package object mongo {

  def toApiResult[F[_]: Functor]
    : FunctionK[AsyncEntityDAO.Result[F, ?], APIResult[F, ?]] =
    new FunctionK[AsyncEntityDAO.Result[F, ?], APIResult[F, ?]] {
      override def apply[A](fa: AsyncEntityDAO.Result[F, A]): APIResult[F, A] = {
        fa.leftMap {
          case DBError.NotFound                  => Error.NotFound("Cannot find in DB")
          case DBError.DBLastError(msg)          => Error.DBLastError(msg)
          case DBError.DBException(e, _)         => Error.DBException(e)
          case e @ UpdatedCountErrorDetail(_, _) => Error.FailedToPersist(e.getMessage())
          case DBError.WriteError(details) =>
            Error.FailedToPersist(
              details.map(d => s"code: ${d.code}, msg: ${d.msg}").toList.mkString("\n"))
        }
      }
    }

  def toF[F[_]](
      implicit F: MonadError[F, Throwable]): FunctionK[AsyncEntityDAO.Result[F, ?], F] =
    new FunctionK[AsyncEntityDAO.Result[F, ?], F] {
      override def apply[A](fa: Result[F, A]): F[A] = fa.leftWiden[Throwable].rethrowT
    }

  implicit val idSelector: EntityId => JsObject = lihua.mongo.Query.idSelector

  def convert[F[_]: cats.Functor, A](
      e: F[EntityDAO[AsyncEntityDAO.Result[F, ?], A, Query]])
    : F[EntityDAO[APIResult[F, ?], A, JsObject]] = {
    val functorK = implicitly[FunctorK[EntityDAO[?[_], A, JsObject]]]
    e.map(od => functorK.mapK(od.contramap(Query.fromSelector))(toApiResult[F]))
  }
  def convertF[F[_], A](e: F[EntityDAO[AsyncEntityDAO.Result[F, ?], A, Query]])(
      implicit F: MonadError[F, Throwable]): F[EntityDAO[F, A, JsObject]] = {
    val functorK = implicitly[FunctorK[EntityDAO[?[_], A, JsObject]]]
    e.map(od => functorK.mapK(od.contramap(Query.fromSelector))(toF[F]))
  }

  def crypt[F[_]: Async](implicit config: Config): Option[Crypt[F]] = {
    import net.ceedubs.ficus.Ficus._
    config.as[Option[String]]("mongoDB.secret").map(new CryptTsec[F](_))
  }

  def mongodb[F[_]: Async](implicit
                           shutdownHook: ShutdownHook,
                           config: Config,
                           ex: ExecutionContext): F[MongoDB[F]] =
    MongoDB[F](config, crypt)

  def daos[F[_]: Async](
      implicit
      shutdownHook: ShutdownHook,
      config: Config,
      ex: ExecutionContext
  ): F[(EntityDAO[APIResult[F, ?], Abtest, JsObject],
        EntityDAO[APIResult[F, ?], Feature, JsObject],
        EntityDAO[APIResult[F, ?], KPIDistribution, JsObject])] =
    mongodb.flatMap { implicit m =>
      daosFromMongo
    }

  def daosFromMongo[F[_]: Async](implicit mongoDB: MongoDB[F], ex: ExecutionContext)
    : F[(EntityDAO[APIResult[F, ?], Abtest, JsObject],
         EntityDAO[APIResult[F, ?], Feature, JsObject],
         EntityDAO[APIResult[F, ?], KPIDistribution, JsObject])] = {
    (convert((new AbtestDAOFactory[F]).create),
     convert((new FeatureDAOFactory[F]).create),
     convert((new KPIDistributionDAOFactory[F]).create)).tupled
  }

  def daosResource[F[_]: Async](
      implicit
      config: Config,
      ex: ExecutionContext
  ): Resource[F,
              (EntityDAO[APIResult[F, ?], Abtest, JsObject],
               EntityDAO[APIResult[F, ?], Feature, JsObject],
               EntityDAO[APIResult[F, ?], KPIDistribution, JsObject])] =
    MongoDB
      .resource[F](
        config,
        crypt
      )
      .evalMap { implicit m =>
        daosFromMongo
      }

  def daosResourceF[F[_]: Async](
      implicit
      config: Config,
      ex: ExecutionContext
  ): Resource[F,
              (EntityDAO[F, Abtest, JsObject],
               EntityDAO[F, Feature, JsObject],
               EntityDAO[F, KPIDistribution, JsObject])] =
    MongoDB
      .resource[F](
        config,
        crypt
      )
      .evalMap { implicit m =>
        (convertF((new AbtestDAOFactory[F]).create),
         convertF((new FeatureDAOFactory[F]).create),
         convertF((new KPIDistributionDAOFactory[F]).create)).tupled
      }
}
