package com.iheart.thomas
package analysis
package monitor

import com.iheart.thomas.analysis.Probability
import cats.implicits._
import ExperimentKPIState.{ArmState, Key}
import cats.data.NonEmptyList
import cats.{Functor, MonadThrow}
import com.iheart.thomas.abtest.Error.NotFound
import com.iheart.thomas.utils.time.Period
import enumeratum._

import java.time.Instant
import ExperimentKPIState.ArmsState
import cats.effect.kernel.Clock
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.Specialization.RealtimeMonitor

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

  def asConversions: Option[ExperimentKPIState[Conversions]] =
    arms.head.kpiStats match {
      case _: Conversions =>
        Some(asInstanceOf[ExperimentKPIState[Conversions]])
      case _ => None
    }

  def asPerUserSamplesLnSummary
      : Option[ExperimentKPIState[PerUserSamplesLnSummary]] =
    arms.head.kpiStats match {
      case _: PerUserSamplesLnSummary =>
        Some(asInstanceOf[ExperimentKPIState[PerUserSamplesLnSummary]])
      case _ => None
    }
}

object ExperimentKPIState {
  type ArmsState[+KS <: KPIStats] = NonEmptyList[ArmState[KS]]
  case class Key(
      feature: FeatureName,
      kpi: KPIName,
      specialization: Specialization = RealtimeMonitor) {
    lazy val toStringKey: String =
      kpi.n + "|" + feature + "|" + specialization.entryName
  }
  object Key {
    def parse(string: String): Option[Key] = {
      string.split('|').toList match {
        case kn :: fn :: sp :: Nil =>
          Specialization.withNameOption(sp).map {
            Key(fn, KPIName(kn), _)
          }
        case _ => None
      }
    }
  }

  sealed trait Specialization extends EnumEntry

  object Specialization extends Enum[Specialization] with enumeratum.CatsEnum[Specialization] {

    val values = findValues
    case object RealtimeMonitor extends Specialization
    case object BanditCurrent extends Specialization
  }

  def init[F[_]: Clock: Functor, KS <: KPIStats](
      key: Key,
      arms: NonEmptyList[ArmState[KS]],
      dataPeriod: Period
    ): F[ExperimentKPIState[KS]] =
    utils.time
      .now[F]
      .map(now => ExperimentKPIState[KS](key, arms, dataPeriod, now, now))

  case class ArmState[+KS <: KPIStats](
      name: ArmName,
      kpiStats: KS,
      likelihoodOptimum: Option[Probability]) {

    def sampleSize: Long = {
      (kpiStats: KPIStats) match {
        case Conversions(_, total)                   => total
        case PerUserSamplesLnSummary(_, _, count)    => count
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

  def updateOptimumLikelihood(
      key: Key,
      likelihoods: Map[ArmName, Probability]
    ): F[ExperimentKPIState[KS]]

  def delete(key: Key): F[Option[ExperimentKPIState[KS]]]

}

trait AllExperimentKPIStateRepo[F[_]] {

  def delete(key: Key): F[Option[ExperimentKPIState[KPIStats]]]
  def all: F[Vector[ExperimentKPIState[KPIStats]]]
  def find(key: Key): F[Option[ExperimentKPIState[KPIStats]]]
  def get(key: Key): F[ExperimentKPIState[KPIStats]]

  def updateOptimumLikelihood(
      key: Key,
      likelihoods: Map[ArmName, Probability]
    ): F[ExperimentKPIState[KPIStats]]
}

object AllExperimentKPIStateRepo {
  implicit def default[F[_]: MonadThrow](
      implicit cRepo: ExperimentKPIStateDAO[F, Conversions],
      pRepo: ExperimentKPIStateDAO[F, PerUserSamplesLnSummary],
      kPIRepo: AllKPIRepo[F]
    ): AllExperimentKPIStateRepo[F] =
    new AllExperimentKPIStateRepo[F] {
      class PerformPartial[A](key: Key) {
        def apply[B <: A, C <: A](ifC: F[B], ifA: F[C]): F[A] =
          kPIRepo.get(key.kpi).flatMap {
            case _: ConversionKPI   => ifC.widen
            case _: AccumulativeKPI => ifA.widen
          }
      }
      def perform[A](key: Key): PerformPartial[A] =
        new PerformPartial[A](key)

      def delete(key: Key): F[Option[ExperimentKPIState[KPIStats]]] = {
        perform[Option[ExperimentKPIState[KPIStats]]](key)(
          cRepo.delete(key),
          pRepo.delete(key)
        )
      }

      def all: F[Vector[ExperimentKPIState[KPIStats]]] =
        for {
          cStates <- cRepo.all
          pStates <- pRepo.all
        } yield (cStates.widen[ExperimentKPIState[KPIStats]] ++ pStates
          .widen[ExperimentKPIState[KPIStats]])

      def find(key: Key): F[Option[ExperimentKPIState[KPIStats]]] =
        perform[Option[ExperimentKPIState[KPIStats]]](key)(
          cRepo.find(key),
          pRepo.find(key)
        )

      def get(key: Key): F[ExperimentKPIState[KPIStats]] =
        find(key).flatMap(
          _.liftTo[F](NotFound(key.toStringKey + " is not found in DB"))
        )

      def updateOptimumLikelihood(
          key: Key,
          likelihoods: Map[ArmName, Probability]
        ): F[ExperimentKPIState[KPIStats]] = perform[ExperimentKPIState[KPIStats]](
        key
      )(
        cRepo.updateOptimumLikelihood(key, likelihoods),
        pRepo.updateOptimumLikelihood(key, likelihoods)
      )
    }
}

