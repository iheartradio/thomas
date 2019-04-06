/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas

import cats.Functor
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
import com.iheart.thomas.model.{Abtest, AbtestExtras, Feature}
import lihua.mongo.DBError.UpdatedCountErrorDetail
import play.api.libs.json.{Format, JsObject}

package object mongo {

  def toApiResult[F[_]: Functor]: FunctionK[AsyncEntityDAO.Result[F, ?], APIResult[F, ?]] = new FunctionK[AsyncEntityDAO.Result[F, ?], APIResult[F, ?]] {
    override def apply[A](fa: AsyncEntityDAO.Result[F, A]): APIResult[F, A] = {
      fa.leftMap {
        case DBError.NotFound            => Error.NotFound(None)
        case DBError.DBLastError(msg)    => Error.DBLastError(msg)
        case DBError.DBException(e, _)   => Error.DBException(e)
        case e @ UpdatedCountErrorDetail(_, _)  => Error.FailedToPersist(e.getMessage())
        case DBError.WriteError(details) => Error.FailedToPersist(details.map(d => s"code: ${d.code}, msg: ${d.msg}").toList.mkString("\n"))
      }
    }
  }

  implicit val idSelector: EntityId => JsObject = lihua.mongo.Query.idSelector

  def convert[F[_]: cats.Functor, A](e: F[EntityDAO[AsyncEntityDAO.Result[F, ?], A, Query]]): F[EntityDAO[APIResult[F, ?], A, JsObject]] = {
    val functorK = implicitly[FunctorK[EntityDAO[?[_], A, JsObject]]]
    e.map(od => functorK.mapK(od.contramap(Query.fromSelector))(toApiResult[F]))
  }


  def crypt[F[_]: Async](implicit config: Config): Option[Crypt[F]] = {
    import net.ceedubs.ficus.Ficus._
    config.as[Option[String]]("mongoDB.secret").map(new CryptTsec[F](_))
  }

  def daos[F[_]: Async](
    implicit
    shutdownHook: ShutdownHook,
    config:       Config,
    ex:           ExecutionContext
  ): F[(EntityDAO[APIResult[F, ?], Abtest, JsObject],
         EntityDAO[APIResult[F, ?], AbtestExtras, JsObject],
         EntityDAO[APIResult[F, ?], Feature, JsObject],
         EntityDAO[APIResult[F, ?], KPIDistribution, JsObject])] = {

    MongoDB[F](
      config,
      crypt
    ).flatMap { implicit m =>
      (convert((new AbtestDAOFactory[F]).create),
      convert((new AbtestExtrasDAOFactory[F]).create),
      convert((new FeatureDAOFactory[F]).create),
      convert((new KPIDistributionDAOFactory[F]).create)).tupled
    }
  }
  def daosResource[F[_]: Async](
    implicit
    config:       Config,
    ex:           ExecutionContext
  ): Resource[F, (EntityDAO[APIResult[F, ?], Abtest, JsObject],
         EntityDAO[APIResult[F, ?], AbtestExtras, JsObject],
         EntityDAO[APIResult[F, ?], Feature, JsObject],
         EntityDAO[APIResult[F, ?], KPIDistribution, JsObject])] = {
    import Formats._
    def daoResource[A: Format](factory: EitherTDAOFactory[A, F])(implicit mongoDB: MongoDB[F])
      : Resource[F, EntityDAO[APIResult[F, ?], A, JsObject]] =
      Resource.liftF(convert(factory.create))

    MongoDB.resource[F](
      config,
      crypt
    ).flatMap { implicit m =>
      (daoResource(new AbtestDAOFactory[F]),
        daoResource(new AbtestExtrasDAOFactory[F]),
        daoResource(new FeatureDAOFactory[F]),
        daoResource(new KPIDistributionDAOFactory[F])).tupled
    }
  }
}
