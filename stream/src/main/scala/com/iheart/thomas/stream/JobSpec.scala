package com.iheart.thomas
package stream
import com.iheart.thomas.analysis.KPIName

import java.time.Instant

sealed trait JobSpec extends Serializable with Product {
  def key: String
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
    val key = "UpdateKPIPrior" + kpiName.n
  }

  case class MonitorTest(
      feature: FeatureName,
      kpi: KPIName,
      expiration: Instant)

  case class UpdateBandit(featureName: FeatureName) extends JobSpec {
    val key = "UpdateBandit" + featureName

  }
}
