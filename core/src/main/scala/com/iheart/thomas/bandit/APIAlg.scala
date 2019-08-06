package com.iheart.thomas.bandit

import cats.Monad
import com.iheart.thomas.FeatureName
import com.iheart.thomas.bandit.model.BanditState
import cats.implicits._

trait APIAlg[F[_]] {
  def updateRewardState[R](featureName: FeatureName, r: R)(
      implicit stateDAO: StateDAO[F, R],
      R: RewardState[R]): F[BanditState[R]]
}

object APIAlg {
  implicit def default[F[_]: Monad]: APIAlg[F] = new APIAlg[F] {
    def updateRewardState[R](featureName: FeatureName, r: R)(
        implicit stateDAO: StateDAO[F, R],
        R: RewardState[R]): F[BanditState[R]] =
      for {
        lastState <- stateDAO.get(featureName)
        updated = lastState.copy(rewardStateSoFar = lastState.rewardStateSoFar |+| r)
        stored <- stateDAO.upsert(updated)
      } yield stored
  }
}
