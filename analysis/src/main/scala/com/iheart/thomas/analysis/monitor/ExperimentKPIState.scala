package com.iheart.thomas
package analysis
package monitor

import com.iheart.thomas.analysis.Probability
import cats.implicits._
import ExperimentKPIState.{ArmState, Key}
import cats.data.NonEmptyList
import cats.{Functor, MonadThrow}
import cats.effect.Timer
import com.iheart.thomas.abtest.Error.NotFound
import com.iheart.thomas.utils.time.Period

import java.time.Instant
import ExperimentKPIState.ArmsState
case class ExperimentKPIState[+KS <: KPIStats](
    key: Key,
    arms: ArmsState[KS],
    dataPeriod: Period,
    lastUpdated: Instant,
    start: Instant) {

  def armsStateMap: Map[ArmName, KS] =
    arms.map(as => (as.name, as.kpiStats)).toList.toMap

  def distribution: Map[ArmName, Probability] =
    arms.toList.mapFilter(as => as.likelihoodOptimum.map((as.name, _))).toMap

  def getArm(armName: ArmName): Option[ArmState[KS]] =
    arms.find(_.name === armName)

}

object ExperimentKPIState {
  type ArmsState[+KS <: KPIStats] = NonEmptyList[ArmState[KS]]

  case class Key(
      feature: FeatureName,
      kpi: KPIName) {
    def toStringKey = feature + "|" + kpi.n
  }

  def init[F[_]: Timer: Functor, KS <: KPIStats](
      key: Key,
      arms: NonEmptyList[ArmState[KS]],
      dataPeriod: Period
    ): F[ExperimentKPIState[KS]] =
    utils.time
      .now[F]
      .map(now => ExperimentKPIState[KS](key, arms, dataPeriod, now, now))

  def parseKey(string: String): Option[Key] = {
    val split = string.split('|')
    if (split.length != 2) None
    else Some(Key(split.head, KPIName(split.last)))
  }

  case class ArmState[+KS <: KPIStats](
      name: ArmName,
      kpiStats: KS,
      likelihoodOptimum: Option[Probability]) {

    def sampleSize: Long = {
      kpiStats match {
        case c: Conversions                   => c.total
        case samples: PerUserSamplesLnSummary => samples.count
      }
    }
  }
}

trait ExperimentKPIStateDAO[F[_], KS <: KPIStats] {

  def get(key: Key): F[ExperimentKPIState[KS]]
  def all: F[Vector[ExperimentKPIState[KS]]]
  def find(key: Key): F[Option[ExperimentKPIState[KS]]]

  def upsert(
      key: Key
    )(update: (ArmsState[KS], Period) => (ArmsState[KS], Period)
    )(ifEmpty: => (ArmsState[KS], Period)
    ): F[ExperimentKPIState[KS]]

  def delete(key: Key): F[Option[ExperimentKPIState[KS]]]

}

trait AllExperimentKPIStateRepo[F[_]] {

  def delete(key: Key): F[Option[ExperimentKPIState[KPIStats]]]
  def all: F[Vector[ExperimentKPIState[KPIStats]]]
  def find(key: Key): F[Option[ExperimentKPIState[KPIStats]]]
  def get(key: Key): F[ExperimentKPIState[KPIStats]]
}

object AllExperimentKPIStateRepo {
  implicit def default[F[_]: MonadThrow](
      implicit cRepo: ExperimentKPIStateDAO[F, Conversions],
      pRepo: ExperimentKPIStateDAO[F, PerUserSamplesLnSummary]
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

      def get(key: Key): F[ExperimentKPIState[KPIStats]] =
        find(key).flatMap(
          _.liftTo[F](NotFound(key.toStringKey + " is not found in DB"))
        )

    }
}
