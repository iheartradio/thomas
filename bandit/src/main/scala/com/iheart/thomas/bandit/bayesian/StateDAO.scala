package com.iheart.thomas
package bandit
package bayesian

private[thomas] trait StateDAO[F[_], R] {
  def insert(state: BanditState[R]): F[BanditState[R]]

  def updateArms(
      featureName: FeatureName,
      update: List[ArmState[R]] => F[List[ArmState[R]]]
    ): F[BanditState[R]]

  def remove(featureName: FeatureName): F[Unit]

  def get(featureName: FeatureName): F[BanditState[R]]
}
