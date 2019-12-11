package com.iheart.thomas
package bandit

import java.time.OffsetDateTime

import com.iheart.thomas.analysis.KPIName

case class BanditSpec(
    feature: FeatureName,
    arms: List[ArmName],
    author: String,
    start: OffsetDateTime,
    title: String,
    kpiName: KPIName,
    minimumSizeChange: Double = 0.01)
