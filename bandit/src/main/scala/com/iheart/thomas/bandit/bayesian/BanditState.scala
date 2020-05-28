package com.iheart.thomas
package bandit
package bayesian

import java.time.Instant

import com.iheart.thomas.analysis.Probability
import cats.implicits._

case class BanditState[R](
    feature: FeatureName,
    arms: List[ArmState[R]],
    iterationStart: Instant,
    version: Long,
    historical: Option[Map[ArmName, R]] = None) {

  def rewardState: Map[ArmName, R] =
    arms.map(as => (as.name, as.rewardState)).toMap

  def distribution: Map[ArmName, Probability] =
    arms.mapFilter(as => as.likelihoodOptimum.map((as.name, _))).toMap

  def getArm(armName: ArmName): Option[ArmState[R]] =
    arms.find(_.name === armName)

}

case class ArmState[R](
    name: ArmName,
    rewardState: R,
    likelihoodOptimum: Option[Probability])
