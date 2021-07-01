package com.iheart.thomas
package dynamo

import java.time.Instant
import java.util.concurrent.TimeUnit
import cats.effect.{Async, Concurrent, Timer}
import cats.implicits._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import com.iheart.thomas.FeatureName
import com.iheart.thomas.analysis.KPIStats
import com.iheart.thomas.bandit.`package`.ArmState
import com.iheart.thomas.bandit.bayesian.{
  BanditSettings,
  BanditSettingsDAO,
  BanditStateDepr,
  StateDAODepre
}
import org.scanamo.{ConditionNotMet, DynamoFormat}
import org.scanamo.syntax._
import org.scanamo.update.UpdateExpression

import concurrent.duration._
import DynamoFormats._

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

  implicit def banditState[F[_]: Async, R <: KPIStats](
      implicit dynamoClient: DynamoDbAsyncClient,
      bsformat: DynamoFormat[BanditStateDepr[R]],
      armformat: DynamoFormat[ArmState[R]],
      rFormat: DynamoFormat[R],
      T: Timer[F]
    ): StateDAODepre[F, R] =
    new ScanamoDAOHelperStringKey[F, BanditStateDepr[R]](
      banditStateTableName,
      banditKeyName,
      dynamoClient
    ) with StateDAODepre[F, R] {

      def updateArms(
          featureName: FeatureName,
          update: List[ArmState[R]] => F[List[ArmState[R]]]
        ): F[BanditStateDepr[R]] =
        updateSafe(featureName) { bs =>
          update(bs.arms).map(ua => Some(set("arms", ua)))
        }.map(r => r._2.getOrElse(r._1))

      def newIteration(
          featureName: FeatureName,
          expirationDuration: FiniteDuration,
          updateArmsHistory: (Option[Map[ArmName, R]],
              List[ArmState[R]]) => F[(Map[ArmName, R], List[ArmState[R]])]
        ): F[Option[BanditStateDepr[R]]] =
        updateSafe(featureName) { bs =>
          for {
            epochMS <- T.clock.realTime(TimeUnit.MILLISECONDS)
            newArmsHistory <- updateArmsHistory(bs.historical, bs.arms)
          } yield {
            val (newHistory, newArms) = newArmsHistory
            if (
              bs.iterationStart
                .plusNanos(expirationDuration.toNanos)
                .toEpochMilli < epochMS
            )
              Some(
                set("historical", Some(newHistory)) and set(
                  "iterationStart",
                  Instant.ofEpochMilli(epochMS)
                ) and set("arms", newArms)
              )
            else None
          }
        }.map(_._2)

      def updateSafe(
          featureName: FeatureName,
          keepRetrying: Boolean = true
        )(update: BanditStateDepr[R] => F[Option[UpdateExpression]]
        ): F[(BanditStateDepr[R], Option[BanditStateDepr[R]])] = {
        val updateF = for {
          existing <- get(featureName)
          updatedExpO <- update(existing)
          updated <- updatedExpO.traverse { updateExp =>
            toF(
              sc.exec(
                table
                  .when("version" === existing.version)
                  .update(
                    banditKeyName === featureName,
                    updateExp and set(
                      "version",
                      (existing.version + 1L)
                    )
                  )
              )
            )
          }
        } yield (existing, updated)

        if (keepRetrying) {
          import retry._
          retryingOnSomeErrors(
            RetryPolicies.constantDelay[F](40.milliseconds),
            { (e: Throwable) =>
              e match {
                case ScanamoError(ConditionNotMet(_)) => true
                case _                                => false
              }
            },
            (_: Throwable, _) => Async[F].unit
          )(updateF)
        } else updateF
      }

    }

}
