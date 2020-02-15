package com.iheart.thomas
package bandit
package single

import cats.Monad
import cats.implicits._

trait SingleChoiceBanditAPIAlg[F[_]] {
  def updateRewardState[R](
      featureName: FeatureName,
      r: R
    )(implicit stateDAO: StateDAO[F, SingleChoiceBanditState[R]],
      R: RewardState[R]
    ): F[SingleChoiceBanditState[R]]
}

object SingleChoiceBanditAPIAlg {
  implicit def default[F[_]: Monad]: SingleChoiceBanditAPIAlg[F] =
    new SingleChoiceBanditAPIAlg[F] {
      def updateRewardState[R](
          featureName: FeatureName,
          r: R
        )(implicit stateDAO: StateDAO[F, SingleChoiceBanditState[R]],
          R: RewardState[R]
        ): F[SingleChoiceBanditState[R]] =
        for {
          lastState <- stateDAO.get(featureName)
          updated = lastState.copy(
            rewardStateSoFar = lastState.rewardStateSoFar |+| r
          )
          stored <- stateDAO.update(updated)
        } yield stored
    }
}
