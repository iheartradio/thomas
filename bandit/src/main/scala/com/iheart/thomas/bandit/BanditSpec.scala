package com.iheart.thomas
package bandit

import java.time.Instant

case class BanditSpec(feature: FeatureName,
                      arms: List[ArmName],
                      author: String,
                      start: Instant,
                      title: String)
