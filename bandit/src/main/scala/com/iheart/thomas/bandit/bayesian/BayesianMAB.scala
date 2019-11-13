package com.iheart.thomas.bandit
package bayesian

import com.iheart.thomas.FeatureName
import com.iheart.thomas.abtest.model.Abtest
import com.iheart.thomas.analysis.KPIName
import lihua.Entity

case class BayesianMAB[R](
    abtest: Entity[Abtest],
    state: BanditState[R]) {
  def feature: FeatureName = abtest.data.feature
  def kpiName: KPIName = state.kpiName
}
