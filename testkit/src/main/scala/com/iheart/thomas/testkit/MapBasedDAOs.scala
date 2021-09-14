package com.iheart.thomas
package testkit

import cats.effect.{Async, Sync}
import cats.implicits._
import com.iheart.thomas.abtest.Error.NotFound
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{ArmsState, Key}
import com.iheart.thomas.analysis.monitor.{ExperimentKPIState, ExperimentKPIStateDAO}
import com.iheart.thomas.analysis.{
  ConversionKPI,
  KPIName,
  KPIRepo,
  KPIStats,
  Probability,
  QueryAccumulativeKPI
}
import com.iheart.thomas.stream.{Job, JobDAO}
import com.iheart.thomas.utils.time.Period

import java.time.Instant
import scala.collection.concurrent._
import scala.util.control.NoStackTrace

object MapBasedDAOs {

  case object KeyAlreadyExist extends RuntimeException with NoStackTrace

  abstract class MapBasedDAOs[F[_], A, K](keyOf: A => K)(implicit F: Sync[F]) {

    val map: Map[K, A] = TrieMap.empty[K, A]

    def insertO(a: A): F[Option[A]] =
      F.delay(map.putIfAbsent(keyOf(a), a).fold(a.some)(_ => None))

    def insert(a: A): F[A] =
      insertO(a).flatMap(_.liftTo[F](KeyAlreadyExist))

    def get(k: K): F[A] =
      find(k).flatMap(
        _.liftTo[F](
          NotFound(
            s"Cannot find in the map with key '${k}'. "
          )
        )
      )

    def find(k: K): F[Option[A]] =
      F.delay(map.get(k))

    def all: F[Vector[A]] =
      F.delay {
        map.values.toVector
      }

    def remove(k: K): F[Unit] =
      delete(k).void

    def delete(k: K): F[Option[A]] =
      F.delay(map.remove(k))

    def update(a: A): F[A] =
      updateO(a).flatMap(_.liftTo[F](NotFound(s"${keyOf(a)} is not found")))

    def updateO(a: A): F[Option[A]] =
      F.delay(map.replace(keyOf(a), a).as(a))

    def upsert(a: A): F[A] =
      F.delay(map.put(keyOf(a), a)).as(a)

    def replace(
        old: A,
        newA: A
      ): F[Option[A]] =
      F.delay(if (map.replace(keyOf(newA), old, newA)) newA.some else None)

    def ensure(
        k: K
      )(ifEmpty: => F[A]
      ): F[A] =
      find(k).flatMap(
        _.fold(ifEmpty.flatMap(insert))(_.pure[F])
      )
  }

  def streamJobDAO[F[_]: Sync]: JobDAO[F] =
    new MapBasedDAOs[F, Job, String](_.key) with JobDAO[F] {
      def updateCheckedOut(
          job: Job,
          at: Instant
        ): F[Option[Job]] =
        replace(job, job.copy(checkedOut = Some(at)))

      def setStarted(
          job: Job,
          at: Instant
        ): F[Job] = update(job.copy(started = Some(at)))

    }

  def experimentStateDAO[
      F[_]: Async,
      KS <: KPIStats
    ]: ExperimentKPIStateDAO[F, KS] =
    new MapBasedDAOs[F, ExperimentKPIState[KS], Key](_.key)
      with ExperimentKPIStateDAO[F, KS] {

      def upsert(
          key: Key
        )(updateF: (ArmsState[KS], Period) => (ArmsState[KS], Period)
        )(ifEmpty: => (ArmsState[KS], Period)
        ): F[ExperimentKPIState[KS]] =
        for {
          now <- utils.time.now[F]
          so <- find(key)
          r <- so.fold(
            ExperimentKPIState
              .init[F, KS](key, ifEmpty._1, ifEmpty._2)
              .flatMap(insert)
          ) { s =>
            val (newArms, newPeriod) = updateF(s.arms, s.dataPeriod)
            update(
              s.copy(
                arms = newArms,
                dataPeriod = newPeriod,
                lastUpdated = now
              )
            )
          }

        } yield r

      def updateOptimumLikelihood(
          key: Key,
          likelihoods: scala.collection.immutable.Map[ArmName, Probability]
        ): F[ExperimentKPIState[KS]] =
        get(key).flatMap { s =>
          val newArms = s.arms.map(arm =>
            arm.copy(likelihoodOptimum = likelihoods.get(arm.name))
          )
          update(
            s.copy(
              arms = newArms
            )
          )
        }

    }

  def conversionKPIAlg[F[_]](
      implicit F: Sync[F]
    ): KPIRepo[F, ConversionKPI] =
    new MapBasedDAOs[F, ConversionKPI, KPIName](_.name)
      with KPIRepo[F, ConversionKPI]

  def queryAccumulativeKPIAlg[F[_]](
      implicit F: Sync[F]
    ): KPIRepo[F, QueryAccumulativeKPI] =
    new MapBasedDAOs[F, QueryAccumulativeKPI, KPIName](_.name)
      with KPIRepo[F, QueryAccumulativeKPI]

}
