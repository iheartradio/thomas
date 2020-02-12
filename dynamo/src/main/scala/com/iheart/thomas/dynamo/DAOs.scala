package com.iheart.thomas.dynamo

import cats.effect.{Async, Timer}
import cats.implicits._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType
import com.iheart.thomas.FeatureName
import com.iheart.thomas.abtest.Error.NotFound
import com.iheart.thomas.bandit.bayesian.{ArmState, BanditState, StateDAO}
import lihua.dynamo.{ScanamoDAOHelper, ScanamoManagement}
import org.scanamo.PutReturn.Nothing
import org.scanamo.query.AttributeNotExists
import org.scanamo.{ConditionNotMet, DynamoFormat, ScanamoError}
import org.scanamo.syntax._
import concurrent.duration._
import scala.util.control.NoStackTrace
object DAOs extends ScanamoManagement {
  val banditStateTableName = "ds-bandit-state"
  val banditStateKeyName = "feature"
  val banditStateKeys = Seq(banditStateKeyName -> ScalarAttributeType.S)
  def ensureBanditStateTable[F[_]: Async](
      readCapacity: Long,
      writeCapacity: Long
    )(implicit dc: AmazonDynamoDBAsync
    ): F[Unit] =
    ensureTable(
      dc,
      banditStateTableName,
      banditStateKeys,
      readCapacity,
      writeCapacity
    )

  def banditState[F[_]: Async: Timer, R](
      implicit dynamoClient: AmazonDynamoDBAsync,
      bsformat: DynamoFormat[BanditState[R]],
      armformat: DynamoFormat[ArmState[R]]
    ): StateDAO[F, R] =
    new ScanamoDAOHelper[F, BanditState[R]](banditStateTableName, dynamoClient)
    with StateDAO[F, R] {

      def toF[E <: ScanamoError, A](e: F[Either[E, A]]): F[A] =
        e.flatMap(_.leftMap(DynamoError(_)).liftTo[F])

      def toF[E <: ScanamoError, A](
          e: F[Option[Either[E, A]]],
          noneErr: Throwable
        ): F[A] =
        e.flatMap(_.liftTo[F](noneErr).flatMap(_.leftMap(DynamoError(_)).liftTo[F]))

      def insert(state: BanditState[R]): F[BanditState[R]] = {
        val toInsert = state.copy(version = 0L)
        for {
          r <- toF(
            sc.exec(
                table
                  .given(AttributeNotExists(banditStateKeyName))
                  .putAndReturn(Nothing)(toInsert)
              )
              .map(_.getOrElse(toInsert.asRight))
          )

        } yield r
      }

      def updateArms(
          featureName: FeatureName,
          update: List[ArmState[R]] => F[List[ArmState[R]]]
        ): F[BanditState[R]] = {
        import retry._
        retryingOnSomeErrors(
          RetryPolicies.constantDelay[F](40.milliseconds), { (e: Throwable) =>
            e match {
              case DynamoError(ConditionNotMet(_)) => true
              case _                               => false
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
                  banditStateKeyName -> featureName,
                  set("arms" -> updatedArms) and set(
                    "version" -> (existing.version + 1L)
                  )
                )
            )
          )
        } yield updated)
      }

      def get(featureName: FeatureName): F[BanditState[R]] =
        toF(
          sc.exec(table.get(banditStateKeyName -> featureName)),
          NotFound(
            s"Cannot find in DB bandit whose feature name is '$featureName'. "
          )
        )

      def remove(featureName: FeatureName): F[Unit] =
        sc.exec(table.delete(banditStateKeyName -> featureName)).void
    }

  case class DynamoError(e: ScanamoError)
      extends RuntimeException
      with NoStackTrace {
    override def toString = e.toString
  }
  case object UnexpectedNoneDynamoResult extends RuntimeException with NoStackTrace
}
