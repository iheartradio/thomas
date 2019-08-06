package com.iheart.thomas
package bandit

import bandit.model._

trait AlgorithmAlgebra[F[_], RewardStateT] {
  type State = BanditState[RewardStateT]

  def chooseArm(state: State): F[State]

  def initialState(spec: BanditSpec): F[State]
}
