package com.iheart.thomas.analysis

import com.iheart.thomas.{ArmName, FeatureName}

import java.time.Instant
import scala.annotation.implicitAmbiguous

@implicitAmbiguous(
  "Query $Event for $K. If you don't need this. Use KPIEventQuery.alwaysFail"
)
trait KPIEventQuery[F[_], K <: KPI, Event] {
  def apply(
      k: K,
      at: Instant
    ): F[Event]

  def apply(
      k: K,
      feature: FeatureName,
      at: Instant
    ): F[Map[ArmName, Event]]
}
