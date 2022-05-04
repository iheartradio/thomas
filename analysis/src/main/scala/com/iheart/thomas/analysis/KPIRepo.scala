package com.iheart.thomas.analysis

import cats.data.ValidatedNel
import cats.syntax.all._
import cats.{FlatMap, MonadThrow}
import com.iheart.thomas.abtest.Error.NotFound
import com.iheart.thomas.analysis.KPIRepo.{InvalidKPI, validate}
import com.iheart.thomas.analysis.bayesian.models.{BetaModel, LogNormalModel}

import scala.util.control.NoStackTrace

trait KPIRepo[F[_], K <: KPI] {

  protected def insert(newKpi: K): F[K]

  def create(
      newKpi: K
    )(implicit F: MonadThrow[F]
    ): F[K] = //todo: add AllKPIRepo deps to ensure kpi name uniqueness.
    validate(newKpi)
      .leftMap(es => InvalidKPI(es.mkString_("; ")))
      .liftTo[F] *> insert(newKpi)

  def update(k: K): F[K]

  def remove(name: KPIName): F[Unit]

  def find(name: KPIName): F[Option[K]]

  def all: F[Vector[K]]

  def get(name: KPIName): F[K]

  def update(name: KPIName)(f: K => K)(implicit F: FlatMap[F]): F[K] =
    get(name).flatMap(k => update(f(k)))
}

object KPIRepo {

  case class InvalidKPI(override val getMessage: String)
      extends RuntimeException
      with NoStackTrace

  def validate(newKpi: KPI): ValidatedNel[String, Unit] =
    KPIName.fromString(newKpi.name.n).toValidatedNel.void *> {
      newKpi match {
        case ck: ConversionKPI =>
          BetaModel.validate(ck.model).void
        case ak: AccumulativeKPI =>
          LogNormalModel.validate(ak.model).void
      }
    }
}

trait AllKPIRepo[F[_]] {
  def all: F[Vector[KPI]]
  def find(name: KPIName): F[Option[KPI]]
  def get(name: KPIName): F[KPI]
  def delete(name: KPIName): F[Option[Unit]]
}

object AllKPIRepo {
  implicit def default[F[_]: MonadThrow](
      implicit cRepo: KPIRepo[F, ConversionKPI],
      aRepo: KPIRepo[F, QueryAccumulativeKPI]
    ): AllKPIRepo[F] =
    new AllKPIRepo[F] {
      def all: F[Vector[KPI]] =
        for {
          cs <- cRepo.all
          as <- aRepo.all
        } yield (cs.widen[KPI] ++ as
          .widen[KPI])

      def find(name: KPIName): F[Option[KPI]] =
        cRepo.find(name).flatMap { r =>
          r.fold(aRepo.find(name).widen[Option[KPI]])(_ => r.pure[F].widen)
        }

      def delete(name: KPIName): F[Option[Unit]] = {
        find(name).flatMap(_.traverse {
          case c: ConversionKPI        => cRepo.remove(c.name)
          case a: QueryAccumulativeKPI => aRepo.remove(a.name)
        })
      }

      def get(name: KPIName): F[KPI] =
        find(name).flatMap(
          _.liftTo[F](
            NotFound("Cannot find KPI named " + name + " is not found in DB")
          )
        )
    }
}
