package com.iheart.thomas.dynamo

import cats.effect.{Async, Concurrent, Timer}
import com.iheart.thomas.analysis._
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{ArmState, Key}
import com.iheart.thomas.analysis.monitor.{ExperimentKPIState, ExperimentKPIStateDAO}
import com.iheart.thomas.dynamo.DynamoFormats._
import org.scanamo.DynamoFormat
import org.scanamo.syntax._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

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

  implicit def conversionKPIAlg[F[_]: Async](
      implicit dynamoClient: DynamoDbAsyncClient
    ): KPIRepo[F, ConversionKPI] =
    new ScanamoDAOHelperStringLikeKey[F, ConversionKPI, KPIName](
      conversionKPITableName,
      conversionKPIKeyName,
      dynamoClient
    ) with KPIRepo[F, ConversionKPI]

  implicit def expStateTimeStamp: WithTimeStamp[ExperimentKPIState[_]] =
    (a: ExperimentKPIState[_]) => a.lastUpdated

  implicit def experimentKPIStateConversionDAO[F[_]: Async](
      implicit dynamoClient: DynamoDbAsyncClient
    ): ExperimentKPIStateDAO[F, Conversions] = experimentKPIStateDAO[F, Conversions]

  def experimentKPIStateDAO[F[_]: Async, KS <: KPIStats](
      implicit dynamoClient: DynamoDbAsyncClient,
      asFormat: DynamoFormat[ArmState[KS]],
      sFormat: DynamoFormat[ExperimentKPIState[KS]]
    ): ExperimentKPIStateDAO[F, KS] =
    new ScanamoDAOHelperStringFormatKey[F, ExperimentKPIState[KS], Key](
      experimentKPIStateTableName,
      experimentKPIStateKeyName,
      dynamoClient
    ) with ExperimentKPIStateDAO[F, KS]
      with AtomicUpdatable[F, ExperimentKPIState[KS], Key] {
      protected def stringKey(k: Key) = k.toStringKey
      import retry._
      def updateState(
          key: Key
        )(updateArms: List[ArmState[KS]] => List[ArmState[KS]]
        )(implicit T: Timer[F]
        ): F[ExperimentKPIState[KS]] = {

        atomicUpdate(key, Some(RetryPolicies.constantDelay[F](40.milliseconds))) {
          state =>
            set("arms", updateArms(state.arms))
        }
      }

    }

}
