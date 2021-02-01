package com.iheart.thomas.dynamo

import cats.effect.{Resource, Sync}
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.services.dynamodbv2.{
  AmazonDynamoDBAsync,
  AmazonDynamoDBAsyncClient
}
import io.estatico.newtype.Coercible
import pureconfig.ConfigSource

object `package` {

  implicit def coercible[A]: Coercible[A, A] = new Coercible[A, A] {}

  def client[F[_]](
      config: ClientConfig
    )(implicit F: Sync[F]
    ): Resource[F, AmazonDynamoDBAsync] = {
    import config._
    Resource.make(
      F.delay(
        AmazonDynamoDBAsyncClient
          .asyncBuilder()
          .withCredentials(
            new AWSStaticCredentialsProvider(
              new BasicAWSCredentials(accessKey, secretKey)
            )
          )
          .withRegion(region)
          //          .withEndpointConfiguration(
          //            new EndpointConfiguration(serviceEndpoint, signingRegion)
          //          )
          .build()
      )
    )(c => F.delay(c.shutdown()))

  }

  def client[F[_]: Sync](cfg: ConfigSource): Resource[F, AmazonDynamoDBAsync] = {
    import pureconfig.generic.auto._
    import pureconfig.module.catseffect._
    Resource
      .liftF(cfg.loadF[F, ClientConfig])
      .flatMap(client[F](_))
  }
}

case class ClientConfig(
    accessKey: String,
    secretKey: String,
    region: String)
