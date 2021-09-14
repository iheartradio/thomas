package com.iheart.thomas
package bandit.tracking

import com.iheart.thomas.abtest.model.Abtest
import com.iheart.thomas.analysis.KPIStats
import com.iheart.thomas.analysis.monitor.ExperimentKPIState
import com.iheart.thomas.tracking.Event

sealed abstract class BanditEvent extends Event

object BanditEvent {

  object BanditPolicyUpdate {
    case object Initiated extends BanditEvent

    case class Calculated(newState: ExperimentKPIState[KPIStats]) extends BanditEvent

    case class Reallocated(test: Abtest) extends BanditEvent

    case object UpdatePolicyAllRunningTriggered extends BanditEvent
  }

}
