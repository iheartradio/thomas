package com.iheart.thomas
package bandit
package bayesian

import java.time.Instant

import cats.Monoid
import com.iheart.thomas.analysis.Probability
import cats.implicits._

case class BayesianState[RewardStateT](
    spec: BanditSpec,
    arms: List[ArmState[RewardStateT]],
    start: Instant
) extends BanditState[RewardStateT] {

  override def rewardState: Map[ArmName, RewardStateT] =
    arms.map(as => (as.name, as.rewardState)).toMap

  def updateArms(rewards: Map[ArmName, RewardStateT])(
      implicit RS: Monoid[RewardStateT]): BayesianState[RewardStateT] =
    copy(arms = arms.map { arm =>
      arm.copy(
        rewardState = rewards.get(arm.name).fold(arm.rewardState)(arm.rewardState |+| _))
    })

}

case class ArmState[RewardStateT](name: ArmName,
                                  rewardState: RewardStateT,
                                  likelihoodOptimum: Probability)
