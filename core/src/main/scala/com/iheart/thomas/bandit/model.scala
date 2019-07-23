package com.iheart.thomas
package bandit

import tempus.time.FreePeriod

object model {

  case class OctopusSpec(
    arms: List[GroupName],
    feature: FeatureName,
    title: String)

  case class KPIRecord(
    featureName: FeatureName,
    armName: GroupName,
    value: KPIValue,
    period: FreePeriod)

}
