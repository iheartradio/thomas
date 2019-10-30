package com.iheart.thomas.dynamo

import cats.effect.{Resource, Sync}
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.dynamodbv2.{
  AmazonDynamoDBAsync,
  AmazonDynamoDBAsyncClient
}

object `package` {

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
          .withEndpointConfiguration(
            new EndpointConfiguration(serviceEndpoint, signingRegion)
          )
          .build()
      )
    )(c => F.delay(c.shutdown()))

  }
}

case class ClientConfig(
    accessKey: String,
    secretKey: String,
    serviceEndpoint: String,
    signingRegion: String)
