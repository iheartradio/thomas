package com.iheart.thomas.bandit

import com.iheart.thomas.FeatureName
import com.iheart.thomas.bandit.types.{ArmName, ExpectedReward}

case class BanditSpec(initArms: Map[ArmName, ExpectedReward],
                      feature: FeatureName,
                      title: String)
