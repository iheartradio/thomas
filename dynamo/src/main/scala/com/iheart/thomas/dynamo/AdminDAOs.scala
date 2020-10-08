package com.iheart.thomas.dynamo

import cats.effect.Async
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.admin.{AuthRecord, AuthRecordDAO, User, UserDAO}
import lihua.dynamo.ScanamoManagement
import DynamoFormats._
import cats.implicits._

object AdminDAOs extends ScanamoManagement {
  val authTableName = "ds-abtest-auth"
  val authKeyName = "id"
  val authKey = ScanamoDAOHelperStringKey.keyOf(authKeyName)

  val userTableName = "ds-abtest-user"
  val userKeyName = "username"
  val userKey = ScanamoDAOHelperStringKey.keyOf(userKeyName)

  def ensureAuthTables[F[_]: Async](
      readCapacity: Long,
      writeCapacity: Long
    )(implicit dc: AmazonDynamoDBAsync
    ): F[Unit] =
    ensureTable(
      dc,
      authTableName,
      Seq(authKey),
      readCapacity,
      writeCapacity
    ) *> ensureTable(dc, userTableName, Seq(userKey), readCapacity, writeCapacity)

  implicit def authRecordDAO[F[_]: Async](
      implicit dynamoClient: AmazonDynamoDBAsync
    ): AuthRecordDAO[F] =
    new ScanamoDAOHelperStringKey[F, AuthRecord](
      authTableName,
      authKeyName,
      dynamoClient
    ) with AuthRecordDAO[F]

  implicit def userDAO[F[_]: Async](
      implicit dynamoClient: AmazonDynamoDBAsync
    ): UserDAO[F] =
    new ScanamoDAOHelperStringKey[F, User](
      userTableName,
      userKeyName,
      dynamoClient
    ) with UserDAO[F]
}
