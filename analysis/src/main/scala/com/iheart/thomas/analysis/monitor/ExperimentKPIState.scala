package com.iheart.thomas
package analysis
package monitor

import com.iheart.thomas.analysis.Probability
import cats.implicits._
import ExperimentKPIState.{ArmState, Key}
import cats.{Functor, MonadThrow, Applicative}
import cats.effect.Timer
import com.iheart.thomas.abtest.Error.NotFound

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

  def init[F[_]: Timer: Functor, KS <: KPIStats](
      key: Key
    ): F[ExperimentKPIState[KS]] =
    utils.time
      .now[F]
      .map(now => ExperimentKPIState[KS](key, Nil, now, now))

  def parseKey(string: String): Option[Key] = {
    val split = string.split('|')
    if (split.length != 2) None
    else Some(Key(split.head, KPIName(split.last)))
  }

  case class ArmState[+KS <: KPIStats](
      name: ArmName,
      kpiStats: KS,
      likelihoodOptimum: Option[Probability]) {
    def readyForEvaluation: Boolean = {
      kpiStats match {
        case c: Conversions                   => c.total > 100
        case samples: PerUserSamplesLnSummary => samples.count > 1000
      }
    }
  }
}

trait ExperimentKPIStateDAO[F[_], KS <: KPIStats] {

  private[analysis] def ensure(
      key: Key
    )(s: => F[ExperimentKPIState[KS]]
    ): F[ExperimentKPIState[KS]]

  def get(key: Key): F[ExperimentKPIState[KS]]
  def all: F[Vector[ExperimentKPIState[KS]]]
  def find(key: Key): F[Option[ExperimentKPIState[KS]]]

  def update(
      key: Key
    )(updateArms: List[ArmState[KS]] => List[ArmState[KS]]
    ): F[ExperimentKPIState[KS]]

  def reset(key: Key)(implicit F: Applicative[F]): F[ExperimentKPIState[KS]] =
    delete(key) *> init(key)

  def delete(key: Key): F[Option[ExperimentKPIState[KS]]]

  def init(
      key: Key
    ): F[ExperimentKPIState[KS]]
}

trait AllExperimentKPIStateRepo[F[_]] {

  def delete(key: Key): F[Option[ExperimentKPIState[KPIStats]]]
  def all: F[Vector[ExperimentKPIState[KPIStats]]]
  def find(key: Key): F[Option[ExperimentKPIState[KPIStats]]]
  def get(key: Key): F[ExperimentKPIState[KPIStats]]
  def reset(key: Key): F[ExperimentKPIState[KPIStats]]
}

object AllExperimentKPIStateRepo {
  implicit def default[F[_]: MonadThrow](
      implicit cRepo: ExperimentKPIStateDAO[F, Conversions],
      pRepo: ExperimentKPIStateDAO[F, PerUserSamplesLnSummary],
      kpiRepo: AllKPIRepo[F]
    ): AllExperimentKPIStateRepo[F] =
    new AllExperimentKPIStateRepo[F] {

      def delete(key: Key): F[Option[ExperimentKPIState[KPIStats]]] =
        cRepo
          .delete(key)
          .flatMap(r =>
            r.fold(pRepo.delete(key).widen[Option[ExperimentKPIState[KPIStats]]])(
              _ => r.pure[F].widen
            )
          )

      def all: F[Vector[ExperimentKPIState[KPIStats]]] =
        for {
          cStates <- cRepo.all
          pStates <- pRepo.all
        } yield (cStates.widen[ExperimentKPIState[KPIStats]] ++ pStates
          .widen[ExperimentKPIState[KPIStats]])

      def find(key: Key): F[Option[ExperimentKPIState[KPIStats]]] =
        cRepo
          .find(key)
          .flatMap(r =>
            r.fold(pRepo.find(key).widen[Option[ExperimentKPIState[KPIStats]]])(_ =>
              r.pure[F].widen
            )
          )

      def reset(key: Key): F[ExperimentKPIState[KPIStats]] =
        kpiRepo.get(key.kpi).flatMap {
          case _: ConversionKPI   => cRepo.reset(key).widen
          case _: AccumulativeKPI => pRepo.reset(key).widen
        }

      def get(key: Key): F[ExperimentKPIState[KPIStats]] =
        find(key).flatMap(
          _.liftTo[F](NotFound(key.toStringKey + " is not found in DB"))
        )

    }
}
