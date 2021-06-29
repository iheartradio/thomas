package com.iheart.thomas
package bandit

import java.time.OffsetDateTime

import com.iheart.thomas.abtest.model.{GroupMeta, GroupSize}
import com.iheart.thomas.bandit.bayesian.BanditSettings

case class BanditSpec(
    arms: List[ArmSpec],
    start: OffsetDateTime,
    settings: BanditSettings) {
  def feature: FeatureName = settings.feature
}

case class ArmSpec(
    name: ArmName,
    initialSize: Option[GroupSize] = None,
    meta: Option[GroupMeta] = None)
