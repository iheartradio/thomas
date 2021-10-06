package com.iheart.thomas.dynamo

import cats.effect.Async
import com.iheart.thomas.ArmName
import com.iheart.thomas.analysis._
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{
  ArmState,
  ArmsState,
  Key
}
import com.iheart.thomas.analysis.monitor.{ExperimentKPIState, ExperimentKPIStateDAO}
import com.iheart.thomas.dynamo.DynamoFormats._
import com.iheart.thomas.utils.time.Period
import org.scanamo.DynamoFormat
import org.scanamo.syntax._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

import scala.concurrent.duration._

object AnalysisDAOs extends ScanamoManagement {
  val conversionKPITableName = "ds-abtest-conversion-kpi"
  val queryAccumulativeKPITableName = "ds-abtest-query-accumulative-kpi"
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
      (queryAccumulativeKPITableName, kPIKey),
      (conversionKPIStateTableName, experimentKPIStateKey),
      (perUserSamplesKPIStateTableName, experimentKPIStateKey)
    )

  def ensureAnalysisTables[F[_]: Async](
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
    ): KPIRepo[F, QueryAccumulativeKPI] =
    kPIRepo[F, QueryAccumulativeKPI](queryAccumulativeKPITableName)

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

  implicit def experimentKPIStateConversionDAO[F[_]: Async](
      implicit dynamoClient: DynamoDbAsyncClient
    ): ExperimentKPIStateDAO[F, Conversions] =
    experimentKPIStateDAO[F, Conversions](conversionKPIStateTableName)

  implicit def experimentKPIStatePerUserSamplesDAO[F[_]: Async](
      implicit dynamoClient: DynamoDbAsyncClient
    ): ExperimentKPIStateDAO[F, PerUserSamplesLnSummary] =
    experimentKPIStateDAO[F, PerUserSamplesLnSummary](
      perUserSamplesKPIStateTableName
    )

  def experimentKPIStateDAO[F[_], KS <: KPIStats](
      tableName: String
    )(implicit dynamoClient: DynamoDbAsyncClient,
      dynamoFormat: DynamoFormat[ExperimentKPIState[KS]],
      armFormat: DynamoFormat[ArmState[KS]],
      F: Async[F]
    ): ExperimentKPIStateDAO[F, KS] =
    new ScanamoDAOHelperStringFormatKey[F, ExperimentKPIState[KS], Key](
      tableName,
      experimentKPIStateKeyName,
      dynamoClient
    ) with ExperimentKPIStateDAO[F, KS]
      with AtomicUpdatable[F, ExperimentKPIState[KS], Key] {

      protected def stringKey(k: Key) = k.toStringKey
      import retry._
      def updateOptimumLikelihood(
          key: Key,
          likelihoods: Map[ArmName, Probability]
        ): F[ExperimentKPIState[KS]] =
        atomicUpdate(key) { state =>
          val newArms = state.arms.map(arm =>
            arm.copy(likelihoodOptimum = likelihoods.get(arm.name))
          )
          set("arms", newArms)
        }

      def upsert(
          key: Key
        )(update: (ArmsState[KS], Period) => (ArmsState[KS], Period)
        )(ifEmpty: => (ArmsState[KS], Period)
        ): F[ExperimentKPIState[KS]] = {

        atomicUpsert(key, Some(RetryPolicies.constantDelay[F](40.milliseconds))) {
          state =>
            val (updatedArms, updatedPeriod) = update(state.arms, state.dataPeriod)
            set("arms", updatedArms) and
              set("dataPeriod", updatedPeriod)
        }(F.defer(ExperimentKPIState.init[F, KS](key, ifEmpty._1, ifEmpty._2)))
      }

    }

}
