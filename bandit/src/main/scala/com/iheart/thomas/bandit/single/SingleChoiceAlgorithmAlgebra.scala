package com.iheart.thomas
package bandit
package single

import com.iheart.thomas.bandit.BanditSpec.EmptySubSettings

trait SingleChoiceAlgorithmAlgebra[F[_], RewardStateT] {

  type State = SingleChoiceBanditState[RewardStateT]

  def chooseArm(state: State): F[State]

  def initialState(spec: BanditSpec[EmptySubSettings.type]): F[State]
}
