package com.iheart.thomas
package bandit

import java.time.Instant

import cats.Id
import breeze.stats.distributions.Bernoulli
import model._
import cats.implicits._
import RewardState.nonInheritedOps._
import scala.util.Random

class EpsilonGreedyAlgorithm[RewardStateT](initEpsilon: Double)(
    implicit RewardStateT: RewardState[RewardStateT])
    extends AlgorithmAlgebra[Id, RewardStateT] {

  def chooseArm(state: State): State = {

    val count = state.chosenArm.chosenCount.toDouble

    val reward = state.rewardStateSoFar.toReward
    val expectation = (((count - 1d) / count) * state.chosenArm.expectedReward) +
      ((1d / count.toDouble) * reward)

    val updatedState = state.updateChosenArmExpectedReward(expectation)
    val updatedRewards = updatedState.expectedRewards

    def exploit = updatedRewards.maxBy(_._2)._1
    def explore =
      updatedState.allArms.get(Random.nextInt(state.allArms.size).toLong).get.name

    val pick = if (Bernoulli.distribution(state.epsilon).draw()) exploit else explore

    updatedState.pickNewArm(pick)

  }

  def initialState(spec: BanditSpec): State = {
    val allArms = spec.initArms.toList.map { case (name, er) => ArmState(name, er, 0L) }
    BanditState[RewardStateT](
      spec = spec,
      chosenArm = allArms.head,
      otherArms = allArms.tail,
      rewardStateSoFar = RewardStateT.empty,
      start = Instant.now,
      epsilon = initEpsilon
    )
  }
}
