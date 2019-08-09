package com.iheart.thomas.bandit.bayesian

import java.time.Instant

import com.iheart.thomas.FeatureName
import com.iheart.thomas.abtest.model.Abtest
import com.iheart.thomas.analysis.KPIName
import com.iheart.thomas.bandit.BanditSpec
import com.iheart.thomas.bandit.`package`.ArmName
import lihua.Entity

trait BayesianMABAlg[F[_], R] {
  def updateRewardState(featureName: FeatureName,
                        rewardState: Map[ArmName, R]): F[BayesianState[R]]

  def init(banditSpec: BanditSpec,
           author: String,
           start: Instant): F[(Entity[Abtest], BayesianState[R])]

  def currentState(featureName: FeatureName): F[(Entity[Abtest], BayesianState[R])]

  def reallocate(featureName: FeatureName, kpiName: KPIName): F[BayesianState[R]]

}
