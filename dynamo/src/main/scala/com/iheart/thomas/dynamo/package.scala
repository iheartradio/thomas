package com.iheart.thomas.dynamo

import cats.effect.{Resource, Sync}
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import io.estatico.newtype.Coercible
import pureconfig.ConfigSource
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.regions.Region

import java.net.URI

object `package` {

  implicit def coercible[A]: Coercible[A, A] = new Coercible[A, A] {}

  def client[F[_]](
      config: ClientConfig
    )(implicit F: Sync[F]
    ): Resource[F, DynamoDbAsyncClient] = {
    import config._
    Resource.make(
      F.delay {
        val builder =
          DynamoDbAsyncClient
            .builder()
            .region(Region.of(region))
            .credentialsProvider(() =>
              AwsBasicCredentials.create(accessKey, secretKey)
            )
        config.overrideEndpoint.fold(builder.build())(ep =>
          builder.endpointOverride(URI.create(ep)).build()
        )
      }
    )(c => F.delay(c.close()))

  }

  def client[F[_]: Sync](cfg: ConfigSource): Resource[F, DynamoDbAsyncClient] = {
    import pureconfig.generic.auto._
    import pureconfig.module.catseffect._
    Resource
      .eval(cfg.loadF[F, ClientConfig])
      .flatMap(client[F](_))
  }
}

case class ClientConfig(
    accessKey: String,
    secretKey: String,
    region: String,
    overrideEndpoint: Option[String] = None)
