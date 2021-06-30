package com.iheart.thomas
package bandit.tracking

import com.iheart.thomas.abtest.model.Abtest
import com.iheart.thomas.analysis.KPIStats
import com.iheart.thomas.analysis.monitor.{ExperimentKPIState}
import com.iheart.thomas.bandit.bayesian.BanditState
import com.iheart.thomas.tracking.Event

sealed abstract class BanditEvent extends Event

object BanditEvent {

  object BanditPolicyUpdate {
    case class Initiated(currentState: BanditState[_ <: KPIStats])
        extends BanditEvent

    case class NewIterationStarted(currentState: BanditState[_ <: KPIStats])
        extends BanditEvent

    case class CalculatedDeprecated(newState: BanditState[_ <: KPIStats])
        extends BanditEvent

    case class Calculated(newState: ExperimentKPIState[KPIStats]) extends BanditEvent

    case class Reallocated(test: Abtest) extends BanditEvent

    case object UpdatePolicyAllRunningTriggered extends BanditEvent
  }

  object BanditKPIUpdate {
    case class Updated[R <: KPIStats](state: BanditState[R]) extends BanditEvent
    case object UpdateStreamStarted extends BanditEvent
    case class Error(e: Throwable) extends BanditEvent {
      override def toString = "Error when updating bandit: " + e.toString
    }
    case class NewSetOfRunningBanditsDetected(features: Seq[FeatureName])
        extends BanditEvent

  }

}
