package com.iheart.thomas
package bandit
import cats.Monoid
import types._
import simulacrum.typeclass

@typeclass(excludeParents = List("Monoid"))
trait RewardState[A] extends Monoid[A] {
  def toReward(a: A): Reward
}

object RewardState {
  implicit val conversionInstance: RewardState[Conversion] = new RewardState[Conversion] {
    def toReward(a: Conversion): Reward = a.converted.toDouble / a.total.toDouble

    def combine(x: Conversion, y: Conversion): Conversion =
      Conversion(total = x.total + y.total, converted = x.converted + y.converted)

    def empty: Conversion = Conversion(0L, 0L)
  }
}
