package com.iheart.thomas.analysis

import cats.data.ValidatedNel
import cats.implicits._
import cats.{FlatMap, MonadThrow}
import com.iheart.thomas.analysis.KPIRepo.{InvalidKPI, validate}
import com.iheart.thomas.analysis.bayesian.models.{BetaModel, LogNormalModel}

import scala.util.control.NoStackTrace

trait KPIRepo[F[_], K <: KPI] {

  protected def insert(newKpi: K): F[K]

  def create(newKpi: K)(implicit F: MonadThrow[F]): F[K] =
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
