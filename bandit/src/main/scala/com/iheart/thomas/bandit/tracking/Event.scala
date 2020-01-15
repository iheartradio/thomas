package com.iheart.thomas.bandit.tracking

import com.iheart.thomas.abtest.model.Abtest
import com.iheart.thomas.analysis.Conversions
import com.iheart.thomas.bandit.bayesian.BanditState

sealed abstract class Event extends Product with Serializable

object Event {

  object ConversionBanditReallocation {
    case class Initiated(currentState: BanditState[Conversions]) extends Event

    case class Calculated(newState: BanditState[Conversions]) extends Event

    case class Reallocated(test: Abtest) extends Event

    case object ReallocationAllRunningTriggered extends Event
  }

  case class BanditKPIUpdated[R](state: BanditState[R]) extends Event
  case object BanditKPIUpdateStreamStarted extends Event

}
