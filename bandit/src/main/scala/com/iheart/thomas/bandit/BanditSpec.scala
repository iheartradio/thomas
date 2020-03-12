package com.iheart.thomas
package bandit

import java.time.OffsetDateTime

import com.iheart.thomas.bandit.bayesian.BanditSettings

case class BanditSpec[S](
    arms: List[ArmName],
    start: OffsetDateTime,
    settings: BanditSettings[S]) {
  def feature: FeatureName = settings.feature
}

object BanditSpec {
  case object EmptySubSettings
}
