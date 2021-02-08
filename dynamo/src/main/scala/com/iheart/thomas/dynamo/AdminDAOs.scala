package com.iheart.thomas.dynamo

import cats.effect.Async
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.admin.{AuthRecord, AuthRecordDAO, User, UserDAO}
import DynamoFormats._
import cats.implicits._
import com.iheart.thomas.stream.{Job, JobDAO}
import org.scanamo.syntax._

import java.time.Instant

object AdminDAOs extends ScanamoManagement {
  val authTableName = "ds-abtest-auth"
  val authKeyName = "id"
  val authKey = ScanamoDAOHelperStringKey.keyOf(authKeyName)

  val userTableName = "ds-abtest-user"
  val userKeyName = "username"
  val userKey = ScanamoDAOHelperStringKey.keyOf(userKeyName)

  val streamJobTableName = "ds-abtest-stream-job"
  val streamJobKeyName = "key"
  val streamJobKey = ScanamoDAOHelperStringKey.keyOf(streamJobKeyName)

  val tables = List(
    (authTableName, authKey),
    (userTableName, userKey),
    (streamJobTableName, streamJobKey)
  )

  def ensureAuthTables[F[_]: Async](
      readCapacity: Long,
      writeCapacity: Long
    )(implicit dc: AmazonDynamoDBAsync
    ): F[Unit] = ensureTables(tables, readCapacity, writeCapacity)

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

  implicit def streamJobDAO[F[_]: Async](
      implicit dynamoClient: AmazonDynamoDBAsync
    ): JobDAO[F] =
    new ScanamoDAOHelperStringKey[F, Job](
      streamJobTableName,
      streamJobKeyName,
      dynamoClient
    ) with JobDAO[F] {

      def updateCheckedOut(
          job: Job,
          at: Instant
        ): F[Option[Job]] = {
        val cond = streamJobKeyName -> job.key
        val setV = set("checkedOut" -> Some(at))
        sc.exec(
            job.checkedOut
              .fold(
                table
                  .given(attributeNotExists("checkedOut"))
                  .update(cond, setV)
              )(c =>
                table
                  .given("checkedOut" -> c)
                  .update(cond, setV)
              )
          )
          .map(_.toOption)
      }

      def setStarted(
          job: Job,
          at: Instant
        ): F[Job] = update(job.key, set("started" -> Some(at)))
    }
}
