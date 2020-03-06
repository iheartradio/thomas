package com.iheart.thomas.bandit.bayesian

import java.time.OffsetDateTime

import com.iheart.thomas.FeatureName
import com.iheart.thomas.bandit.BanditSpec
import com.iheart.thomas.bandit.`package`.ArmName

/**
  * Abtest based Bayesian Multi Arm Bandit Algebra
  * @tparam F
  * @tparam R
  */
trait BayesianMABAlg[F[_], R, S] {
  def updateRewardState(
      featureName: FeatureName,
      rewardState: Map[ArmName, R]
    ): F[BanditState[R]]

  type Bandit = BayesianMAB[R, S]
  def init(banditSpec: BanditSpec[S]): F[Bandit]

  def currentState(featureName: FeatureName): F[Bandit]

  def getAll: F[Vector[Bandit]]

  def runningBandits(time: Option[OffsetDateTime] = None): F[Vector[Bandit]]

  def reallocate(featureName: FeatureName): F[Bandit]

  def delete(featureName: FeatureName): F[Unit]

}
