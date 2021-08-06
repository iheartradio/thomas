package com.iheart.thomas
package dynamo

import cats.effect.{Async, Concurrent, Timer}
import com.iheart.thomas.bandit.bayesian.{BanditSpec, BanditSpecDAO}
import com.iheart.thomas.dynamo.DynamoFormats._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

object BanditsDAOs extends ScanamoManagement {
  val banditStateTableName = "ds-bandit-state"
  val banditSpecTableName = "ds-bandit-setting"
  val banditKeyName = "feature"
  val banditKey = ScanamoDAOHelperStringKey.keyOf(banditKeyName)

  val tables =
    List((banditStateTableName, banditKey), (banditSpecTableName, banditKey))

  def ensureBanditTables[F[_]: Concurrent](
      readCapacity: Long,
      writeCapacity: Long
    )(implicit dc: DynamoDbAsyncClient
    ): F[Unit] =
    ensureTables(tables, readCapacity, writeCapacity)

  implicit def banditSpec[F[_]: Async: Timer](
      implicit dynamoClient: DynamoDbAsyncClient
    ): BanditSpecDAO[F] =
    new ScanamoDAOHelperStringKey[F, BanditSpec](
      banditSpecTableName,
      banditKeyName,
      dynamoClient
    ) with BanditSpecDAO[F]

}
