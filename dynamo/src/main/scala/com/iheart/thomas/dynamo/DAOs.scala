package com.iheart.thomas.dynamo

import cats.effect.{Async, Timer}
import cats.implicits._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.FeatureName
import com.iheart.thomas.bandit.bayesian.{
  ArmState,
  BanditSettings,
  BanditSettingsDAO,
  BanditState,
  StateDAO
}
import lihua.dynamo.ScanamoEntityDAO.ScanamoError
import lihua.dynamo.ScanamoManagement
import org.scanamo.{ConditionNotMet, DynamoFormat, ScanamoError}
import org.scanamo.syntax._

import concurrent.duration._
import scala.util.control.NoStackTrace
object DAOs extends ScanamoManagement {
  val banditStateTableName = "ds-bandit-state"
  val banditSettingsTableName = "ds-bandit-setting"
  val banditKeyName = "feature"
  val banditKey = ScanamoDAOHelperStringKey.keyOf(banditKeyName)

  def ensureBanditTables[F[_]: Async](
      readCapacity: Long,
      writeCapacity: Long
    )(implicit dc: AmazonDynamoDBAsync
    ): F[Unit] =
    ensureTable(
      dc,
      banditStateTableName,
      Seq(banditKey),
      readCapacity,
      writeCapacity
    ) *> ensureTable( //todo: separate the capacity between the two tables
      dc,
      banditSettingsTableName,
      Seq(banditKey),
      readCapacity,
      writeCapacity
    )

  def banditSettings[F[_]: Async: Timer, S](
      implicit dynamoClient: AmazonDynamoDBAsync,
      bsformat: DynamoFormat[BanditSettings[S]]
    ): BanditSettingsDAO[F, S] =
    new ScanamoDAOHelperStringKey[F, BanditSettings[S]](
      banditSettingsTableName,
      banditKeyName,
      dynamoClient
    ) with BanditSettingsDAO[F, S]

  def banditState[F[_]: Async: Timer, R](
      implicit dynamoClient: AmazonDynamoDBAsync,
      bsformat: DynamoFormat[BanditState[R]],
      armformat: DynamoFormat[ArmState[R]]
    ): StateDAO[F, R] =
    new ScanamoDAOHelperStringKey[F, BanditState[R]](
      banditStateTableName,
      banditKeyName,
      dynamoClient
    ) with StateDAO[F, R] {

      def updateArms(
          featureName: FeatureName,
          update: List[ArmState[R]] => F[List[ArmState[R]]]
        ): F[BanditState[R]] = {
        import retry._
        retryingOnSomeErrors(
          RetryPolicies.constantDelay[F](40.milliseconds), { (e: Throwable) =>
            e match {
              case ScanamoError(ConditionNotMet(_)) => true
              case _                                => false
            }
          },
          (_: Throwable, _) => Async[F].unit
        )(for {
          existing <- get(featureName)
          updatedArms <- update(existing.arms)
          updated <- toF(
            sc.exec(
              table
                .given("version" -> existing.version)
                .update(
                  banditKeyName -> featureName,
                  set("arms" -> updatedArms) and set(
                    "version" -> (existing.version + 1L)
                  )
                )
            )
          )
        } yield updated)
      }

    }

  case object UnexpectedNoneDynamoResult extends RuntimeException with NoStackTrace
}
