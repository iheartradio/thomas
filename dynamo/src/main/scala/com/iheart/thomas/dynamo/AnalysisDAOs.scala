package com.iheart.thomas.dynamo

import cats.effect.{Async, Concurrent, Timer}
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{ArmState, Key}
import com.iheart.thomas.analysis.monitor.{ExperimentKPIState, ExperimentKPIStateDAO}
import com.iheart.thomas.analysis._, bayesian.models._
import com.iheart.thomas.dynamo.DynamoFormats._
import org.scanamo.DynamoFormat
import org.scanamo.syntax._

import scala.concurrent.duration._

object AnalysisDAOs extends ScanamoManagement {
  val conversionKPITableName = "ds-abtest-conversion-kpi"
  val conversionKPIKeyName = "name"
  val conversionKPIKey = ScanamoDAOHelperStringKey.keyOf(conversionKPIKeyName)

  val experimentKPIStateTableName = "ds-abtest-experiment-kpi-state"
  val experimentKPIStateKeyName = "key"
  val experimentKPIStateKey =
    ScanamoDAOHelperStringKey.keyOf(experimentKPIStateKeyName)

  def tables =
    List(
      (conversionKPITableName, conversionKPIKey),
      (experimentKPIStateTableName, experimentKPIStateKey)
    )

  def ensureAnalysisTables[F[_]: Concurrent](
      readCapacity: Long = 2,
      writeCapacity: Long = 2
    )(implicit dc: DynamoDbAsyncClient
    ): F[Unit] =
    ensureTables(tables, readCapacity, writeCapacity)

  implicit def conversionKPIDAO[F[_]: Async](
      implicit dynamoClient: DynamoDbAsyncClient
    ): ConversionKPIAlg[F] =
    new ScanamoDAOHelperStringLikeKey[F, ConversionKPI, KPIName](
      conversionKPITableName,
      conversionKPIKeyName,
      dynamoClient
    ) with ConversionKPIAlg[F] {
      def setModel(
          name: KPIName,
          model: BetaModel
        ): F[ConversionKPI] =
        toF(
          sc.exec(
            table.update(conversionKPIKeyName === name.n, set("model", model))
          )
        )
    }

  implicit def expStateTimeStamp[R]: WithTimeStamp[ExperimentKPIState[R]] =
    (a: ExperimentKPIState[R]) => a.lastUpdated

  implicit def experimentKPIStateConversionDAO[F[_]: Async](
      implicit dynamoClient: DynamoDbAsyncClient
    ): ExperimentKPIStateDAO[F, Conversions] = experimentKPIStateDAO[F, Conversions]

  def experimentKPIStateDAO[F[_]: Async, R](
      implicit dynamoClient: DynamoDbAsyncClient,
      asFormat: DynamoFormat[ArmState[R]],
      sFormat: DynamoFormat[ExperimentKPIState[R]]
    ): ExperimentKPIStateDAO[F, R] =
    new ScanamoDAOHelperStringFormatKey[F, ExperimentKPIState[R], Key](
      experimentKPIStateTableName,
      experimentKPIStateKeyName,
      dynamoClient
    ) with ExperimentKPIStateDAO[F, R]
      with AtomicUpdatable[F, ExperimentKPIState[R], Key] {
      protected def stringKey(k: Key) = k.toStringKey
      import retry._
      def updateState(
          key: Key
        )(updateArms: List[ArmState[R]] => List[ArmState[R]]
        )(implicit T: Timer[F]
        ): F[ExperimentKPIState[R]] = {

        atomicUpdate(key, Some(RetryPolicies.constantDelay[F](40.milliseconds))) {
          state =>
            set("arms", updateArms(state.arms))
        }
      }

    }

}
