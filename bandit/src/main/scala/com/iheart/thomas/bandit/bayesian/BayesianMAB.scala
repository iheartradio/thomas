package com.iheart.thomas.bandit
package bayesian

import com.iheart.thomas.FeatureName
import com.iheart.thomas.abtest.model.Abtest
import com.iheart.thomas.analysis.{KPIName, KPIStats}
import lihua.Entity

case class BayesianMAB[R <: KPIStats, S](
    abtest: Entity[Abtest],
    settings: BanditSettings[S],
    state: BanditState[R]) {
  def feature: FeatureName = abtest.data.feature
  def kpiName: KPIName = settings.kpiName

}
