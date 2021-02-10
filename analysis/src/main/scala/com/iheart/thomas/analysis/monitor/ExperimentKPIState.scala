package com.iheart.thomas
package analysis
package monitor

import com.iheart.thomas.analysis.Probability
import cats.implicits._
import ExperimentKPIState.{ArmState, Key}
import cats.effect.Timer

import java.time.Instant

case class ExperimentKPIState[R](
    key: Key,
    arms: List[ArmState[R]],
    lastUpdated: Instant) {

  def armsStateMap: Map[ArmName, R] =
    arms.map(as => (as.name, as.kpiStats)).toMap

  def distribution: Map[ArmName, Probability] =
    arms.mapFilter(as => as.likelihoodOptimum.map((as.name, _))).toMap

  def getArm(armName: ArmName): Option[ArmState[R]] =
    arms.find(_.name === armName)

}

object ExperimentKPIState {

  case class Key(
      feature: FeatureName,
      kpi: KPIName) {
    def toStringKey = feature + "|" + kpi.n
  }

  def parseKey(string: String): Option[Key] = {
    val split = string.split('|')
    if (split.length != 2) None
    else Some(Key(split.head, split.last))
  }

  case class ArmState[R](
      name: ArmName,
      kpiStats: R,
      likelihoodOptimum: Option[Probability])

}

trait ExperimentKPIStateDAO[F[_], R] {

  private[analysis] def upsert(s: ExperimentKPIState[R]): F[ExperimentKPIState[R]]

  def get(key: Key): F[ExperimentKPIState[R]]
  def find(key: Key): F[Option[ExperimentKPIState[R]]]
  def updateState(
      key: Key
    )(updateArms: List[ArmState[R]] => List[ArmState[R]]
    )(implicit T: Timer[F]
    ): F[ExperimentKPIState[R]]
}
