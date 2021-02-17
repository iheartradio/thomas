package com.iheart.thomas.stream

import com.iheart.thomas.tracking.Event

sealed trait JobEvent extends Event

object JobEvent {
  case class MessageReceived[M](m: M) extends JobEvent
}
