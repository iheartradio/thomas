package com.iheart.thomas.bandit

import java.time.Instant

import cats.Monoid
import com.iheart.thomas.bandit.types.{ArmName, ExpectedReward, Reward}
import monocle.macros.syntax.lens._
import cats.implicits._

sealed trait BanditState extends Serializable with Product {
  def spec: BanditSpec
  def start: Instant
}

case class SingleArmBanditState[RewardStateT](
    spec: BanditSpec,
    chosenArm: ArmState,
    otherArms: List[ArmState],
    rewardStateSoFar: RewardStateT,
    start: Instant,
    epsilon: Double
) extends BanditState {

  def allArms = chosenArm :: otherArms

  private[bandit] def pickNewArm(armName: ArmName)(
      implicit RewardStateT: Monoid[RewardStateT]): SingleArmBanditState[RewardStateT] =
    copy(
      chosenArm = {
        allArms.find(_.name == armName).get.lens(_.chosenCount).modify(_ + 1)
      },
      otherArms = allArms.filterNot(_.name == armName),
      rewardStateSoFar = RewardStateT.empty,
      start = Instant.now
    )

  private[bandit] def updateRewardState(newReward: RewardStateT)(
      implicit RewardStateT: Monoid[RewardStateT]): SingleArmBanditState[RewardStateT] =
    copy(rewardStateSoFar = rewardStateSoFar |+| newReward)

  private[bandit] def updateChosenArmExpectedReward(
      newReward: Reward): SingleArmBanditState[RewardStateT] =
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
