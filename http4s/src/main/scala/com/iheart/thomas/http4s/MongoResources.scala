package com.iheart.thomas
package http4s

import cats.effect.{Async, Resource}

import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.mongo.DAOs
import com.typesafe.config.Config

import scala.compat.java8.DurationConverters._
import scala.concurrent.ExecutionContext

object MongoResources extends ConfigResource {

  def abtestAlg[F[_]: Async](
      cfg: Config,
      daos: mongo.DAOs[F]
    ): Resource[F, AbtestAlg[F]] = {

    implicit val (abtestDAO, featureDAO) = daos
    val refreshPeriod =
      cfg.getDuration("thomas.abtest.get-groups.ttl").toScala
    AbtestAlg.defaultResource[F](refreshPeriod)
  }

  def abtestAlg[F[_]: Async](
      cfgResourceName: Option[String] = None
    )(implicit ex: ExecutionContext
    ): Resource[F, AbtestAlg[F]] =
    cfg[F](cfgResourceName).flatMap(abtestAlg(_))

  def abtestAlg[F[_]: Async](
      cfg: Config
    )(implicit ex: ExecutionContext
    ): Resource[F, AbtestAlg[F]] =
    dAOs[F](cfg).flatMap(abtestAlg(cfg, _))

  def dAOs[F[_]: Async](
      config: Config
    )(implicit ex: ExecutionContext
    ): Resource[F, DAOs[F]] =
    mongo.daosResource[F](config)

}
