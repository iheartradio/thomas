package com.iheart.thomas
package bandit

trait SingleArmAlgorithmAlgebra[F[_], RewardStateT] {
  type State = SingleArmBanditState[RewardStateT]

  def chooseArm(state: State): F[State]

  def initialState(spec: BanditSpec): F[State]
}
