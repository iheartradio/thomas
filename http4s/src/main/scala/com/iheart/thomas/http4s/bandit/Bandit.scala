package com.iheart.thomas.http4s.bandit

import com.iheart.thomas.FeatureName
import com.iheart.thomas.abtest.model.Abtest
import com.iheart.thomas.analysis.{KPIName, KPIStats}
import com.iheart.thomas.analysis.monitor.ExperimentKPIState
import com.iheart.thomas.bandit.BanditStatus
import com.iheart.thomas.bandit.bayesian.{BanditSpec, BayesianMAB}
import lihua.Entity

case class Bandit(
    abtest: Entity[Abtest],
    spec: BanditSpec,
    state: Option[ExperimentKPIState[KPIStats]],
    status: BanditStatus) {
  def feature: FeatureName = abtest.data.feature
  def kpiName: KPIName = spec.kpiName
}

object Bandit {
  def apply(b: BayesianMAB, status: BanditStatus): Bandit =
    Bandit(b.abtest, b.spec, b.state, status)
}
