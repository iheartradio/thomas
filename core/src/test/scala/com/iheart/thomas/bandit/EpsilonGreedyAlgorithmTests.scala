package com.iheart.thomas.bandit

import com.iheart.thomas.bandit.types.{BanditSpec, Conversion}
import org.scalatest.Matchers
import org.scalatest.funsuite.AnyFunSuiteLike

class EpsilonGreedyAlgorithmTests extends AnyFunSuiteLike with Matchers {

  test("init state reset RewardState") {
    val algo = new EpsilonGreedyAlgorithm[Conversion](
      initEpsilon = 0.9d
    )
    algo
      .initialState(BanditSpec(Map("A" -> 0, "B" -> 0), "a_feature", "some desc"))
      .rewardStateSoFar shouldBe RewardState[Conversion].empty
  }
}
