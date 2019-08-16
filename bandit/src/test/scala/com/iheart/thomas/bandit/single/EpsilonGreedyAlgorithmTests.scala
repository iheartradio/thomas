package com.iheart.thomas
package bandit
package single

import com.iheart.thomas.analysis.Conversions
import org.scalatest.Matchers
import org.scalatest.funsuite.AnyFunSuiteLike

class EpsilonGreedyAlgorithmTests extends AnyFunSuiteLike with Matchers {

  test("init state reset RewardState") {
    val algo = new EpsilonGreedyAlgorithm[Conversions](
      initEpsilon = 0.9d
    )
    algo
      .initialState(BanditSpec(List("A", "B"), "a_feature", "some desc"))
      .rewardStateSoFar shouldBe RewardState[Conversions].empty
  }
}
