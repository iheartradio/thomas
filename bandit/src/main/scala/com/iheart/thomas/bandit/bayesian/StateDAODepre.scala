package com.iheart.thomas
package bandit
package bayesian

import com.iheart.thomas.analysis.KPIStats
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.ArmState

import scala.concurrent.duration.FiniteDuration

private[thomas] trait StateDAODepre[F[_], R <: KPIStats] {
  def insert(state: BanditStateDepr[R]): F[BanditStateDepr[R]]

  def updateArms(
      featureName: FeatureName,
      update: List[ArmState[R]] => F[List[ArmState[R]]]
    ): F[BanditStateDepr[R]]

  /** @param featureName
    *   @param expirationDuration
    * @return
    *   Some new state if a new iteration is started, None otherwise.
    */
  def newIteration(
      featureName: FeatureName,
      expirationDuration: FiniteDuration,
      updateArmsHistory: (Option[Map[ArmName, R]],
          List[ArmState[R]]) => F[(Map[ArmName, R], List[ArmState[R]])]
    ): F[Option[BanditStateDepr[R]]]

  def remove(featureName: FeatureName): F[Unit]

  def get(featureName: FeatureName): F[BanditStateDepr[R]]
}

private[thomas] trait BanditSettingsDAO[F[_]] {
  def insert(
      state: BanditSettings
    ): F[BanditSettings]

  def remove(featureName: FeatureName): F[Unit]

  def get(featureName: FeatureName): F[BanditSettings]

  def update(
      settings: BanditSettings
    ): F[BanditSettings]
}
