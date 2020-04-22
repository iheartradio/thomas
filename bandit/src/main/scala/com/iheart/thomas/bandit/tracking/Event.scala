package com.iheart.thomas.bandit.tracking

import com.iheart.thomas.FeatureName
import com.iheart.thomas.abtest.model.Abtest
import com.iheart.thomas.bandit.bayesian.BanditState

sealed abstract class Event extends Product with Serializable

object Event {

  object BanditPolicyUpdate {
    case class Initiated(currentState: BanditState[_]) extends Event

    case class NewIterationStarted(currentState: BanditState[_]) extends Event

    case class Calculated(newState: BanditState[_]) extends Event

    case class Reallocated(test: Abtest) extends Event

    case object UpdatePolicyAllRunningTriggered extends Event
  }

  object BanditKPIUpdate {
    case class Updated[R](state: BanditState[R]) extends Event
    case object UpdateStreamStarted extends Event
    case class Error(e: Throwable) extends Event {
      override def toString = "Error when updating bandit: " + e.toString
    }
    case class NewSetOfRunningBanditsDetected(features: Seq[FeatureName])
        extends Event

  }

}
