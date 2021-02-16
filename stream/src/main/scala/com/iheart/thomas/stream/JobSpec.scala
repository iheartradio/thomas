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
      until: Instant)
      extends JobSpec {
    val key = UpdateKPIPrior.keyOf(kpiName)
    val description =
      s"Update the prior for KPI $kpiName using ongoing data until $until"
  }

  object UpdateKPIPrior {
    def keyOf(kpiName: KPIName) = "Update_KPI_Prior_For_" + kpiName.n
  }

  case class MonitorTest(
      feature: FeatureName,
      kpi: KPIName,
      until: Instant)
      extends JobSpec {
    val key = MonitorTest.jobKey(feature, kpi)
    val description =
      s"Real time monitor for A/B tests on feature $feature using KPI $kpi. (expires on $until)"
  }

  object MonitorTest {
    def jobKey(
        feature: FeatureName,
        kpi: KPIName
      ) = "Monitor_Test_" + feature + "_With_KPI_" + kpi
  }

  case class RunBandit(featureName: FeatureName) extends JobSpec {
    val key = "Run_Bandit_" + featureName

    val description =
      s"Running Multi Arm Bandit $featureName"

  }
}
