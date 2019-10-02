package com.iheart.thomas
package bandit

import java.time.OffsetDateTime

case class BanditSpec(
    feature: FeatureName,
    arms: List[ArmName],
    author: String,
    start: OffsetDateTime,
    title: String)
