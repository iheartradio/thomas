package com.iheart.thomas.bandit.tracking

import com.iheart.thomas.FeatureName
import com.iheart.thomas.abtest.model.Abtest
import com.iheart.thomas.bandit.bayesian.BanditState
import com.iheart.thomas.tracking.Event

sealed abstract class BanditEvent extends Event

object BanditEvent {

  object BanditPolicyUpdate {
    case class Initiated(currentState: BanditState[_]) extends BanditEvent

    case class NewIterationStarted(currentState: BanditState[_]) extends BanditEvent

    case class Calculated(newState: BanditState[_]) extends BanditEvent

    case class Reallocated(test: Abtest) extends BanditEvent

    case object UpdatePolicyAllRunningTriggered extends BanditEvent
  }

  object BanditKPIUpdate {
    case class Updated[R](state: BanditState[R]) extends BanditEvent
    case object UpdateStreamStarted extends BanditEvent
    case class Error(e: Throwable) extends BanditEvent {
      override def toString = "Error when updating bandit: " + e.toString
    }
    case class NewSetOfRunningBanditsDetected(features: Seq[FeatureName])
        extends BanditEvent

  }

}
