package com.iheart.thomas.stream

import com.iheart.thomas.{ArmName, FeatureName}
import com.iheart.thomas.analysis.QueryAccumulativeKPI
import com.iheart.thomas.tracking.Event

sealed trait JobEvent extends Event

object JobEvent {
  case class MessagesReceived[M](sample: M, size: Int) extends JobEvent
  case class MessagesParseError[M](err: Throwable, rawMsg: M) extends JobEvent
  case class RunningJobsUpdated(jobs: Seq[Job]) extends JobEvent
  case class EventsQueriedForFeature(
      k: QueryAccumulativeKPI,
      feature: FeatureName,
      armsCount: List[(ArmName, Int)])
      extends JobEvent

  case class EventsQueried(
      k: QueryAccumulativeKPI,
      count: Int)
      extends JobEvent

  case class EventQueryInitiated(
      k: QueryAccumulativeKPI)
      extends JobEvent
}
