package com.iheart.thomas
package bandit

import cats.Monoid
import com.iheart.thomas.analysis.Conversions
import simulacrum.typeclass

@typeclass(excludeParents = List("Monoid"))
trait RewardState[A] extends Monoid[A] {
  def toReward(a: A): Reward
}

object RewardState {
  implicit val conversionInstance: RewardState[Conversions] =
    new RewardState[Conversions] {
      def toReward(a: Conversions): Reward = a.converted.toDouble / a.total.toDouble

      def combine(x: Conversions, y: Conversions): Conversions =
        Conversions(total = x.total + y.total, converted = x.converted + y.converted)

      def empty: Conversions = Conversions(0L, 0L)
    }
}
