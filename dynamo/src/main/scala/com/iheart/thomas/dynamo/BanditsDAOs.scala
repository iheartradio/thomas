package com.iheart.thomas
package dynamo

import cats.effect.{Async, Concurrent, Timer}
import com.iheart.thomas.bandit.bayesian.{BanditSettings, BanditSettingsDAO}
import com.iheart.thomas.dynamo.DynamoFormats._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

object BanditsDAOs extends ScanamoManagement {
  val banditStateTableName = "ds-bandit-state"
  val banditSettingsTableName = "ds-bandit-setting"
  val banditKeyName = "feature"
  val banditKey = ScanamoDAOHelperStringKey.keyOf(banditKeyName)

  val tables =
    List((banditStateTableName, banditKey), (banditSettingsTableName, banditKey))

  def ensureBanditTables[F[_]: Concurrent](
      readCapacity: Long,
      writeCapacity: Long
    )(implicit dc: DynamoDbAsyncClient
    ): F[Unit] =
    ensureTables(tables, readCapacity, writeCapacity)

  implicit def banditSettings[F[_]: Async: Timer](
      implicit dynamoClient: DynamoDbAsyncClient
    ): BanditSettingsDAO[F] =
    new ScanamoDAOHelperStringKey[F, BanditSettings](
      banditSettingsTableName,
      banditKeyName,
      dynamoClient
    ) with BanditSettingsDAO[F]

}
