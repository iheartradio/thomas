package com.iheart.thomas
package bandit
package bayesian

import java.time.OffsetDateTime

import cats.Monoid
import com.iheart.thomas.analysis.Probability
import cats.implicits._

case class BanditState[R](
    feature: FeatureName,
    title: String,
    author: String,
    arms: List[ArmState[R]],
    start: OffsetDateTime) {

  def rewardState: Map[ArmName, R] =
    arms.map(as => (as.name, as.rewardState)).toMap

  def updateArms(
      rewards: Map[ArmName, R]
    )(implicit RS: Monoid[R]
    ): BanditState[R] =
    copy(arms = arms.map { arm =>
      arm.copy(
        rewardState = rewards
          .get(arm.name)
          .fold(arm.rewardState)(arm.rewardState |+| _)
      )
    })

}

case class ArmState[R](
    name: ArmName,
    rewardState: R,
    likelihoodOptimum: Probability)
