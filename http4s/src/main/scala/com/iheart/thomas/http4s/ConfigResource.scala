package com.iheart.thomas.http4s

import cats.effect.{Resource, Sync}
import com.typesafe.config.Config
import pureconfig.ConfigSource
import pureconfig.module.catseffect.CatsEffectConfigSource

trait ConfigResource {
  def cfg[F[_]](
      cfgResourceName: Option[String] = None
    )(implicit F: Sync[F]
    ): Resource[F, Config] =
    Resource.eval(
      cfgResourceName
        .fold(ConfigSource.default)(name =>
          ConfigSource
            .resources(name)
            .withFallback(ConfigSource.default)
        )
        .loadF[F, Config]
    )
}

object ConfigResource extends ConfigResource
