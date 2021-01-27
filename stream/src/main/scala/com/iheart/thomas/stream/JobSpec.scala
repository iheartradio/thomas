package com.iheart.thomas
package stream
import com.iheart.thomas.analysis.KPIName

import java.time.Instant

sealed trait JobSpec extends Serializable with Product {
  def key: String
  def description: String
}

object JobSpec {
  type ErrorMsg = String

  /**
    * Update a KPI's prior based a sample
    * @param kpiName
    * @param sampleSize
    */
  case class UpdateKPIPrior(
      kpiName: KPIName,
      sampleSize: Int = 1000)
      extends JobSpec {
    val key = "Update_KPI_Prior_For_" + kpiName.n
    val description =
      s"Update the prior for KPI $kpiName with a sample size of $sampleSize"
  }

  case class MonitorTest(
      feature: FeatureName,
      kpi: KPIName,
      expiration: Instant)
      extends JobSpec {
    val key = "Monitor_Test_" + feature + "_With_KPI_" + kpi
    val description =
      s"Real time monitor for A/B tests on feature $feature using KPI $kpi. (expires on $expiration)"
  }

  case class RunBandit(featureName: FeatureName) extends JobSpec {
    val key = "Run_Bandit_" + featureName

    val description =
      s"Running Multi Arm Bandit $featureName"

  }
}
