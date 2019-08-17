package com.iheart.thomas
package bandit

import java.time.Instant

trait BanditState[R] extends Serializable with Product {
  def spec: BanditSpec
  def start: Instant
  def rewardState: Map[ArmName, R]
}
