package com.iheart.thomas
package bandit

import java.time.OffsetDateTime

import com.iheart.thomas.analysis.KPIName

/**
  *
  * @param feature
  * @param arms
  * @param author
  * @param start
  * @param title
  * @param kpiName
  * @param minimumSizeChange the minimum threshold of group size change. to avoid small fluctuation on statistics change
  * @param initialSampleSize the sample size from which the allocation starts.
  */
case class BanditSpec(
    feature: FeatureName,
    arms: List[ArmName],
    author: String,
    start: OffsetDateTime,
    title: String,
    kpiName: KPIName,
    minimumSizeChange: Double = 0.01,
    initialSampleSize: Int = 0)
