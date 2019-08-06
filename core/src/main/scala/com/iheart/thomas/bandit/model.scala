package com.iheart.thomas
package bandit

import java.time.Instant
import monocle.macros.syntax.lens._
import cats.Monoid
import cats.implicits._

object model {
  type ArmName = GroupName
  type Reward = Double
  type ExpectedReward = Reward

  case class BanditSpec(initArms: Map[ArmName, ExpectedReward],
                        feature: FeatureName,
                        title: String)

  case class BanditState[RewardStateT](
      spec: BanditSpec,
      chosenArm: ArmState,
      otherArms: List[ArmState],
      rewardStateSoFar: RewardStateT,
      start: Instant,
      epsilon: Double
  ) {

    def allArms = chosenArm :: otherArms

    private[bandit] def pickNewArm(armName: ArmName)(
        implicit RewardStateT: Monoid[RewardStateT]): BanditState[RewardStateT] =
      copy(
        chosenArm = {
          allArms.find(_.name == armName).get.lens(_.chosenCount).modify(_ + 1)
        },
        otherArms = allArms.filterNot(_.name == armName),
        rewardStateSoFar = RewardStateT.empty,
        start = Instant.now
      )

    private[bandit] def updateRewardState(newReward: RewardStateT)(
        implicit RewardStateT: Monoid[RewardStateT]): BanditState[RewardStateT] =
      copy(rewardStateSoFar = rewardStateSoFar |+| newReward)

    private[bandit] def updateChosenArmExpectedReward(
        newReward: Reward): BanditState[RewardStateT] =
      this.lens(_.chosenArm.expectedReward).set(newReward)

    def expectedRewards: Map[ArmName, Reward] =
      allArms.map(h => (h.name, h.expectedReward)).toMap
  }

  case class ArmState(
      name: ArmName,
      expectedReward: ExpectedReward,
      chosenCount: Long
  )

  case class Conversion(total: Long, converted: Long)

}
