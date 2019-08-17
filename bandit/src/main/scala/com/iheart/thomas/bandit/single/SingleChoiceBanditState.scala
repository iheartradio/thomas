package com.iheart.thomas.bandit.single

import java.time.Instant

import cats.Monoid
import com.iheart.thomas.bandit.{BanditSpec, BanditState}
import com.iheart.thomas.bandit.`package`.{ArmName, ExpectedReward, Reward}

import cats.Monoid
import monocle.macros.syntax.lens._
import cats.implicits._

case class SingleChoiceBanditState[RewardStateT](
    spec: BanditSpec,
    chosenArm: ArmState,
    otherArms: List[ArmState],
    rewardStateSoFar: RewardStateT,
    start: Instant,
    epsilon: Double
) extends BanditState[RewardStateT] {

  def rewardState: Map[ArmName, RewardStateT] =
    Map(chosenArm.name -> rewardStateSoFar)

  def allArms =
    chosenArm :: otherArms

  def expectedRewards: Map[ArmName, Reward] =
    allArms.map(h => (h.name, h.expectedReward)).toMap

  private[bandit] def pickNewArm(armName: ArmName)(
      implicit RewardStateT: Monoid[RewardStateT])
    : SingleChoiceBanditState[RewardStateT] =
    copy(
      chosenArm = {
        allArms.find(_.name == armName).get.lens(_.chosenCount).modify(_ + 1)
      },
      otherArms = allArms.filterNot(_.name == armName),
      rewardStateSoFar = RewardStateT.empty,
      start = Instant.now
    )

  private[bandit] def updateRewardState(newReward: RewardStateT)(
      implicit RewardStateT: Monoid[RewardStateT])
    : SingleChoiceBanditState[RewardStateT] =
    copy(rewardStateSoFar = rewardStateSoFar |+| newReward)

  private[bandit] def updateChosenArmExpectedReward(
      newReward: Reward): SingleChoiceBanditState[RewardStateT] =
    this.lens(_.chosenArm.expectedReward).set(newReward)

}

case class ArmState(
    name: ArmName,
    expectedReward: ExpectedReward,
    chosenCount: Long
)
