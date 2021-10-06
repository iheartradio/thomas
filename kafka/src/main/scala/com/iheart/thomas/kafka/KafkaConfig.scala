package com.iheart.thomas.kafka

import cats.effect.Sync
import com.typesafe.config.Config
import pureconfig.ConfigSource

case class KafkaConfig(
    kafkaServers: String,
    topic: String,
    groupId: String,
    parseParallelization: Int)

object KafkaConfig {
  def fromConfig[F[_]: Sync](cfg: Config): F[KafkaConfig] = {
    import pureconfig.generic.auto._
    import pureconfig.module.catseffect.syntax._
    ConfigSource.fromConfig(cfg).at("thomas.stream.kafka").loadF[F, KafkaConfig]()
  }
}
