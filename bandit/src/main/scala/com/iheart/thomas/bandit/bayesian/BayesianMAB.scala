package com.iheart.thomas.bandit
package bayesian

import com.iheart.thomas.FeatureName
import com.iheart.thomas.abtest.model.Abtest
import lihua.Entity

case class BayesianMAB[R](
    abtest: Entity[Abtest],
    state: BanditState[R]) {
  def feature: FeatureName = abtest.data.feature
}
