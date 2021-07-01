package com.iheart.thomas
package bandit
package bayesian

import java.time.Instant
import com.iheart.thomas.analysis.{KPIStats, Probability}
import cats.implicits._
import analysis.monitor.ExperimentKPIState.ArmState

case class BanditStateDepr[KS <: KPIStats](
    feature: FeatureName,
    arms: List[ArmState[KS]],
    iterationStart: Instant,
    version: Long,
    historical: Option[Map[ArmName, KS]] = None) {

  def rewardState: Map[ArmName, KS] =
    arms.map(as => (as.name, as.kpiStats)).toMap

  def distribution: Map[ArmName, Probability] =
    arms.mapFilter(as => as.likelihoodOptimum.map((as.name, _))).toMap

  def getArm(armName: ArmName): Option[ArmState[KS]] =
    arms.find(_.name === armName)

}
