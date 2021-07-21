package com.iheart.thomas
package bandit
package bayesian

import com.iheart.thomas.abtest.model.Abtest
import com.iheart.thomas.analysis.{KPIName, KPIStats}
import com.iheart.thomas.analysis.monitor.ExperimentKPIState
import lihua.Entity

case class BayesianMAB(
    abtest: Entity[Abtest],
    spec: BanditSpec,
    state: Option[ExperimentKPIState[KPIStats]]) {
  def feature: FeatureName = abtest.data.feature
  def kpiName: KPIName = spec.kpiName
}
