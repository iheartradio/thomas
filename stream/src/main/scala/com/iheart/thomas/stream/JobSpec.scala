package com.iheart.thomas
package stream
import com.iheart.thomas.analysis.KPIName
import com.iheart.thomas.stream.JobSpec.ProcessSettingsOptional

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

sealed trait JobSpec extends Serializable with Product {
  def key: String
  def description: String
  def processSettings: ProcessSettingsOptional
}

object JobSpec {
  type ErrorMsg = String

  /** Update a KPI's prior based a sample
    * @param kpiName
    *   @param sampleSize
    */
  case class UpdateKPIPrior(
      kpiName: KPIName,
      processSettings: ProcessSettingsOptional)
      extends JobSpec {
    val key = UpdateKPIPrior.keyOf(kpiName)
    val description =
      s"Update the prior for KPI $kpiName using ongoing data}"
  }

  object UpdateKPIPrior {
    def keyOf(kpiName: KPIName) = "Update_KPI_Prior_For_" + kpiName.n
  }

  case class MonitorTest(
      feature: FeatureName,
      kpiName: KPIName,
      processSettings: ProcessSettingsOptional)
      extends JobSpec {
    val key = MonitorTest.jobKey(feature, kpiName)
    val description =
      s"Real time monitor for A/B tests on feature $feature using KPI $kpiName."
  }

  object MonitorTest {
    def jobKey(
        feature: FeatureName,
        kpi: KPIName
      ) = "Monitor_Test_" + feature + "_With_KPI_" + kpi
  }

  case class RunBandit(
      featureName: FeatureName,
      processSettings: ProcessSettingsOptional)
      extends JobSpec {
    val key = "Run_Bandit_" + featureName

    val description =
      s"Running Multi Arm Bandit $featureName"

  }

  case class ProcessSettings(
      frequency: FiniteDuration,
      eventChunkSize: Int,
      expiration: Option[Instant])

  case class ProcessSettingsOptional(
      frequency: Option[FiniteDuration],
      eventChunkSize: Option[Int],
      expiration: Option[Instant])
}
