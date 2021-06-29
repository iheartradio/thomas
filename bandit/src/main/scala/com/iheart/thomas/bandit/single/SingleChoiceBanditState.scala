package com.iheart.thomas
package bandit.single

import java.time.Instant

import cats.Monoid
import com.iheart.thomas.bandit.BanditSpec
import com.iheart.thomas.bandit.{ExpectedReward, Reward}

import cats.Monoid
import monocle.macros.syntax.lens._
import cats.implicits._

case class SingleChoiceBanditState[RewardStateT](
    spec: BanditSpec,
    chosenArm: SingleChoiceArmState,
    otherArms: List[SingleChoiceArmState],
    rewardStateSoFar: RewardStateT,
    start: Instant,
    epsilon: Double) {

  def rewardState: Map[ArmName, RewardStateT] =
    Map(chosenArm.name -> rewardStateSoFar)

  def allArms =
    chosenArm :: otherArms

  def expectedRewards: Map[ArmName, Reward] =
    allArms.map(h => (h.name, h.expectedReward)).toMap

  private[bandit] def pickNewArm(
      armName: ArmName
    )(implicit RewardStateT: Monoid[RewardStateT]
    ): SingleChoiceBanditState[RewardStateT] =
    copy(
      chosenArm = {
        allArms.find(_.name == armName).get.lens(_.chosenCount).modify(_ + 1)
      },
      otherArms = allArms.filterNot(_.name == armName),
      rewardStateSoFar = RewardStateT.empty,
      start = Instant.now
    )

  private[bandit] def updateRewardState(
      newReward: RewardStateT
    )(implicit RewardStateT: Monoid[RewardStateT]
    ): SingleChoiceBanditState[RewardStateT] =
    copy(rewardStateSoFar = rewardStateSoFar |+| newReward)

  private[bandit] def updateChosenArmExpectedReward(
      newReward: Reward
    ): SingleChoiceBanditState[RewardStateT] =
    this.lens(_.chosenArm.expectedReward).set(newReward)

}

case class SingleChoiceArmState(
    name: ArmName,
    expectedReward: ExpectedReward,
    chosenCount: Long)
