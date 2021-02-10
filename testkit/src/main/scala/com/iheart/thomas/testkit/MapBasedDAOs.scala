package com.iheart.thomas.testkit

import cats.effect.{Sync, Timer}
import cats.implicits._
import com.iheart.thomas.TimeUtil
import com.iheart.thomas.abtest.Error.NotFound
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{ArmState, Key}
import com.iheart.thomas.analysis.monitor.{ExperimentKPIState, ExperimentKPIStateDAO}
import com.iheart.thomas.analysis.{
  BetaModel,
  ConversionKPI,
  ConversionKPIAlg,
  KPIName
}
import com.iheart.thomas.stream.{Job, JobDAO}

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
      F.delay(map.remove(k)).void

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

  def experimentStateDAO[F[_]: Sync, R]: ExperimentKPIStateDAO[F, R] =
    new MapBasedDAOs[F, ExperimentKPIState[R], Key](_.key)
      with ExperimentKPIStateDAO[F, R] {

      def updateState(
          key: ExperimentKPIState.Key
        )(updateArms: List[ArmState[R]] => List[ArmState[R]]
        )(implicit T: Timer[F]
        ): F[ExperimentKPIState[R]] =
        for {
          now <- TimeUtil.now[F]
          s <- get(key)
          r <- update(
            s.copy(
              arms = updateArms(s.arms),
              lastUpdated = now
            )
          )
        } yield r
    }

  def conversionKPIAlg[F[_]](implicit F: Sync[F]): ConversionKPIAlg[F] =
    new MapBasedDAOs[F, ConversionKPI, KPIName](_.name) with ConversionKPIAlg[F] {
      def setModel(
          name: KPIName,
          model: BetaModel
        ): F[ConversionKPI] =
        get(name).flatMap(k => update(k.copy(model = model)))
    }

}
