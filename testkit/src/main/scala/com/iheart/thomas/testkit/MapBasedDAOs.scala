package com.iheart.thomas.testkit

import cats.effect.Sync

import collection.concurrent._
import cats.implicits._
import com.iheart.thomas.abtest.Error.NotFound
import com.iheart.thomas.analysis.{
  BetaModel,
  ConversionKPI,
  ConversionKPIDAO,
  KPIName
}
import com.iheart.thomas.stream.{Job, JobDAO}

import java.time.Instant
import scala.util.control.NoStackTrace

object MapBasedDAOs {

  case object KeyAlreadyExist extends RuntimeException with NoStackTrace

  abstract class MapBasedDAOs[F[_], A, K](keyOf: A => K)(implicit F: Sync[F]) {

    val map: Map[K, A] = TrieMap.empty[K, A]

    def insertO(a: A): F[Option[A]] = F.delay(map.putIfAbsent(keyOf(a), a))

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

    def find(k: K): F[Option[A]] = F.delay(map.get(k))

    def all: F[Vector[A]] = map.values.toVector.pure[F]

    def remove(k: K): F[Unit] =
      F.delay(map - k).void

    def update(a: A): F[A] =
      updateO(a).flatMap(_.liftTo[F](NotFound(s"${keyOf(a)} is not found")))

    def updateO(a: A): F[Option[A]] =
      F.delay(map.replace(keyOf(a), a))

    def upsert(a: A): F[A] =
      F.delay(map.put(keyOf(a), a)).as(a)
  }

  implicit def streamJobDAO[F[_]: Sync]: JobDAO[F] =
    new MapBasedDAOs[F, Job, String](_.key) with JobDAO[F] {
      def updateCheckedOut(
          job: Job,
          at: Instant
        ): F[Option[Job]] = updateO(job.copy(checkedOut = Some(at)))
    }

  implicit def conversionKPIDAO[F[_]: Sync]: ConversionKPIDAO[F] =
    new MapBasedDAOs[F, ConversionKPI, KPIName](_.name) with ConversionKPIDAO[F] {
      def updateModel(
          name: KPIName,
          model: BetaModel
        ): F[ConversionKPI] = get(name).flatMap(k => update(k.copy(model = model)))
    }

}
