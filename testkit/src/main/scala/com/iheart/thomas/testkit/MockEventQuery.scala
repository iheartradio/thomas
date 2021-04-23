package com.iheart.thomas.testkit

import cats.effect.IO
import com.iheart.thomas.analysis.{AccumulativeKPI, PerUserSamples}
import com.iheart.thomas.stream.KPIEventQuery

object MockEventQuery {
  implicit val mockEventQuery: KPIEventQuery[IO, AccumulativeKPI, PerUserSamples] =
    KPIEventQuery.alwaysFail[IO, AccumulativeKPI, PerUserSamples]
}
