package com.iheart.thomas
package http4s

import cats.effect.{Async, Concurrent, Resource, Sync, Timer}
import com.iheart.thomas
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.mongo.DAOs
import com.typesafe.config.Config
import pureconfig.ConfigSource
import pureconfig.module.catseffect.CatsEffectConfigSource

import scala.compat.java8.DurationConverters._
import scala.concurrent.ExecutionContext

trait Resources {
  def cfg[F[_]](
      cfgResourceName: Option[String] = None
    )(implicit F: Sync[F]
    ): Resource[F, Config] =
    Resource.liftF(
      cfgResourceName
        .fold(ConfigSource.default)(
          name =>
            ConfigSource
              .resources(name)
              .withFallback(ConfigSource.default)
        )
        .loadF[F, Config]
    )

}

object Resources extends Resources

object MongoResources extends Resources {

  def abtestAlg[F[_]: Timer: Concurrent](
      cfg: Config,
      daos: mongo.DAOs[F]
    ): Resource[F, AbtestAlg[F]] = {

    import thomas.mongo.idSelector
    implicit val (abtestDAO, featureDAO, _) = daos
    val refreshPeriod =
      cfg.getDuration("thomas.abtest.get-groups.ttl").toScala
    AbtestAlg.defaultResource[F](refreshPeriod)
  }

  def abtestAlg[F[_]: Timer: Concurrent](
      cfgResourceName: Option[String] = None
    )(implicit ex: ExecutionContext
    ): Resource[F, AbtestAlg[F]] =
    cfg[F](cfgResourceName).flatMap(abtestAlg(_))

  def abtestAlg[F[_]: Timer: Concurrent](
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
