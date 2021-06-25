package com.iheart.thomas
package bandit

import com.iheart.thomas.analysis.KPIStats

object `package` {

  type Reward = Double
  type ExpectedReward = Reward
  type Weight = Double

  type ArmState[KS <: KPIStats] = analysis.monitor.ExperimentKPIState.ArmState[KS]
  val ArmState = analysis.monitor.ExperimentKPIState.ArmState
}
