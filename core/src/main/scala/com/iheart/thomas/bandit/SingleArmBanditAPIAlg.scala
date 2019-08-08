package com.iheart.thomas
package bandit

import cats.Monad
import cats.implicits._

trait SingleArmBanditAPIAlg[F[_]] {
  def updateRewardState[R](featureName: FeatureName, r: R)(
      implicit stateDAO: BanditStateDAO[F, SingleArmBanditState[R]],
      R: RewardState[R]): F[SingleArmBanditState[R]]
}

object SingleArmBanditAPIAlg {
  implicit def default[F[_]: Monad]: SingleArmBanditAPIAlg[F] =
    new SingleArmBanditAPIAlg[F] {
      def updateRewardState[R](featureName: FeatureName, r: R)(
          implicit stateDAO: BanditStateDAO[F, SingleArmBanditState[R]],
          R: RewardState[R]): F[SingleArmBanditState[R]] =
        for {
          lastState <- stateDAO.get(featureName)
          updated = lastState.copy(rewardStateSoFar = lastState.rewardStateSoFar |+| r)
          stored <- stateDAO.upsert(updated)
        } yield stored
    }
}
