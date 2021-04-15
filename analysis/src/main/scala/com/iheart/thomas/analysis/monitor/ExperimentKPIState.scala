package com.iheart.thomas
package analysis
package monitor

import com.iheart.thomas.analysis.Probability
import cats.implicits._
import ExperimentKPIState.{ArmState, Key}
import cats.effect.Timer

import java.time.Instant

case class ExperimentKPIState[+KS <: KPIStats](
    key: Key,
    arms: List[ArmState[KS]],
    lastUpdated: Instant,
    start: Instant) {

  def armsStateMap: Map[ArmName, KS] =
    arms.map(as => (as.name, as.kpiStats)).toMap

  def distribution: Map[ArmName, Probability] =
    arms.mapFilter(as => as.likelihoodOptimum.map((as.name, _))).toMap

  def getArm(armName: ArmName): Option[ArmState[KS]] =
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
    else Some(Key(split.head, KPIName(split.last)))
  }

  case class ArmState[+KS <: KPIStats](
      name: ArmName,
      kpiStats: KS,
      likelihoodOptimum: Option[Probability])
}

trait ExperimentKPIStateDAO[F[_], KS <: KPIStats] {

  private[analysis] def ensure(
      key: Key
    )(s: => F[ExperimentKPIState[KS]]
    ): F[ExperimentKPIState[KS]]

  def get(key: Key): F[ExperimentKPIState[KS]]
  def remove(key: Key): F[Unit]
  def all: F[Vector[ExperimentKPIState[KS]]]
  def find(key: Key): F[Option[ExperimentKPIState[KS]]]
  def updateState(
      key: Key
    )(updateArms: List[ArmState[KS]] => List[ArmState[KS]]
    )(implicit T: Timer[F]
    ): F[ExperimentKPIState[KS]]
}
