package com.iheart.thomas.bandit.bayesian

import com.iheart.thomas.FeatureName
import com.iheart.thomas.abtest.model.GroupSize
import com.iheart.thomas.analysis.KPIName

import scala.concurrent.duration.FiniteDuration

/**
  *
  * @param feature
  * @param title
  * @param author
  * @param kpiName
  * @param historyRetention
  * @param minimumSizeChange the minimum threshold of group size change. to avoid small fluctuation on statistics change
  * @param initialSampleSize the sample size from which the allocation starts.
  */
case class BanditSettings[SpecificSettings](
    feature: FeatureName,
    title: String,
    author: String,
    kpiName: KPIName,
    minimumSizeChange: Double = 0.001,
    historyRetention: Option[FiniteDuration] = None,
    initialSampleSize: Int = 0,
    maintainExplorationSize: Option[GroupSize] = None,
    iterationDuration: Option[FiniteDuration] = None,
    distSpecificSettings: SpecificSettings)

object BanditSettings {

  case class Conversion(
      eventChunkSize: Int,
      updatePolicyEveryNChunk: Int)
}
