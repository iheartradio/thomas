package com.iheart.thomas.analysis.monitor

import com.iheart.thomas.analysis.KPIStats
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.Key

case class ExperimentKPIHistory[+KS <: KPIStats](
    key: Key,
    history: List[ExperimentKPIState[KS]])

trait ExperimentKPIHistoryRepo[F[_]] {
  def get(key: Key): F[ExperimentKPIHistory[KPIStats]]
  def append(state: ExperimentKPIState[KPIStats]): F[ExperimentKPIHistory[KPIStats]]
  def delete(k: Key): F[Option[ExperimentKPIHistory[KPIStats]]]
}
