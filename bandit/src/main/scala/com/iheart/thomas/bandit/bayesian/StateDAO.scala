package com.iheart.thomas
package bandit
package bayesian

import scala.concurrent.duration.FiniteDuration

private[thomas] trait StateDAO[F[_], R] {
  def insert(state: BanditState[R]): F[BanditState[R]]

  def updateArms(
      featureName: FeatureName,
      update: List[ArmState[R]] => F[List[ArmState[R]]]
    ): F[BanditState[R]]

  /**
    *
    * @param featureName
    * @param expirationDuration
    * @return Some new state if a new iteration is started, None otherwise.
    */
  def newIteration(
      featureName: FeatureName,
      expirationDuration: FiniteDuration,
      updateArmsHistory: (Option[Map[ArmName, R]],
          List[ArmState[R]]) => F[(Map[ArmName, R], List[ArmState[R]])]
    ): F[Option[BanditState[R]]]

  def remove(featureName: FeatureName): F[Unit]

  def get(featureName: FeatureName): F[BanditState[R]]
}

private[thomas] trait BanditSettingsDAO[F[_], SpecificSettings] {
  def insert(
      state: BanditSettings[SpecificSettings]
    ): F[BanditSettings[SpecificSettings]]

  def remove(featureName: FeatureName): F[Unit]

  def get(featureName: FeatureName): F[BanditSettings[SpecificSettings]]

  def update(
      settings: BanditSettings[SpecificSettings]
    ): F[BanditSettings[SpecificSettings]]
}
