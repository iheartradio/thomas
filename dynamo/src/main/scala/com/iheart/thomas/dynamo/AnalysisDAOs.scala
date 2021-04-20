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
  val accumulativeKPITableName = "ds-abtest-accumulative-kpi"
  val kPIKeyName = "name"
  val kPIKey = ScanamoDAOHelperStringKey.keyOf(kPIKeyName)

  val conversionKPIStateTableName = "ds-abtest-conversion-kpi-state"
  val perUserSamplesKPIStateTableName = "ds-abtest-per-user-samples-kpi-state"
  val experimentKPIStateKeyName = "key"
  val experimentKPIStateKey =
    ScanamoDAOHelperStringKey.keyOf(experimentKPIStateKeyName)

  def tables =
    List(
      (conversionKPITableName, kPIKey),
      (accumulativeKPITableName, kPIKey),
      (conversionKPIStateTableName, experimentKPIStateKey),
      (perUserSamplesKPIStateTableName, experimentKPIStateKey)
    )

  def ensureAnalysisTables[F[_]: Concurrent](
      readCapacity: Long = 2,
      writeCapacity: Long = 2
    )(implicit dc: DynamoDbAsyncClient
    ): F[Unit] =
    ensureTables(tables, readCapacity, writeCapacity)

  implicit def conversionKPIRepo[F[_]: Async](
      implicit dynamoClient: DynamoDbAsyncClient
    ): KPIRepo[F, ConversionKPI] = kPIRepo[F, ConversionKPI](conversionKPITableName)

  implicit def accumulativeKPIRepo[F[_]: Async](
      implicit dynamoClient: DynamoDbAsyncClient
    ): KPIRepo[F, AccumulativeKPI] =
    kPIRepo[F, AccumulativeKPI](accumulativeKPITableName)

  def kPIRepo[F[_]: Async, K <: KPI](
      tableName: String
    )(implicit dynamoClient: DynamoDbAsyncClient,
      dynamoFormat: DynamoFormat[K]
    ): KPIRepo[F, K] =
    new ScanamoDAOHelperStringLikeKey[F, K, KPIName](
      tableName,
      kPIKeyName,
      dynamoClient
    ) with KPIRepo[F, K]

  implicit def expStateTimeStamp: WithTimeStamp[ExperimentKPIState[_]] =
    (a: ExperimentKPIState[_]) => a.lastUpdated

  implicit def experimentKPIStateConversionDAO[F[_]: Async: Timer](
      implicit dynamoClient: DynamoDbAsyncClient
    ): ExperimentKPIStateDAO[F, Conversions] =
    experimentKPIStateDAO[F, Conversions](conversionKPIStateTableName)

  implicit def experimentKPIStatePerUserSamplesDAO[F[_]: Async: Timer](
      implicit dynamoClient: DynamoDbAsyncClient
    ): ExperimentKPIStateDAO[F, PerUserSamplesSummary] =
    experimentKPIStateDAO[F, PerUserSamplesSummary](perUserSamplesKPIStateTableName)

  def experimentKPIStateDAO[F[_]: Async: Timer, KS <: KPIStats](
      tableName: String
    )(implicit dynamoClient: DynamoDbAsyncClient,
      dynamoFormat: DynamoFormat[ExperimentKPIState[KS]],
      armFormat: DynamoFormat[ArmState[KS]]
    ): ExperimentKPIStateDAO[F, KS] =
    new ScanamoDAOHelperStringFormatKey[F, ExperimentKPIState[KS], Key](
      tableName,
      experimentKPIStateKeyName,
      dynamoClient
    ) with ExperimentKPIStateDAO[F, KS]
      with AtomicUpdatable[F, ExperimentKPIState[KS], Key] {

      protected def stringKey(k: Key) = k.toStringKey
      import retry._
      def update(
          key: Key
        )(updateArms: List[ArmState[KS]] => List[ArmState[KS]]
        ): F[ExperimentKPIState[KS]] = {

        atomicUpdate(key, Some(RetryPolicies.constantDelay[F](40.milliseconds))) {
          state =>
            set("arms", updateArms(state.arms))
        }
      }

      def init(
          key: Key
        ): F[ExperimentKPIState[KS]] = {
        ensure(key)(ExperimentKPIState.init[F, KS](key))
      }

    }

}
