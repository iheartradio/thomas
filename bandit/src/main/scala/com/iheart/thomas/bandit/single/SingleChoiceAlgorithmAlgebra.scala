package com.iheart.thomas
package bandit
package single

trait SingleChoiceAlgorithmAlgebra[F[_], RewardStateT] {

  type State = SingleChoiceBanditState[RewardStateT]

  def chooseArm(state: State): F[State]

  def initialState(spec: BanditSpec): F[State]
}
