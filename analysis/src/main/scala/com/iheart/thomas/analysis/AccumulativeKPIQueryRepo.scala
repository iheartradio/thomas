package com.iheart.thomas.analysis

import cats.{Applicative, Functor}
import com.iheart.thomas.analysis.MessageQuery.FieldName
import com.iheart.thomas.analysis.Query.DefaultParamValue
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration
trait AccumulativeKPIQueryRepo[F[_]] {
  def queries: F[List[PerUserSamplesQuery[F]]]
  def implemented: Boolean = true
  def findQuery(
      name: QueryName
    )(implicit F: Functor[F]
    ): F[Option[PerUserSamplesQuery[F]]] =
    queries.map(_.find(_.name == name))
}

object AccumulativeKPIQueryRepo {
  def unsupported[F[_]: Applicative]: AccumulativeKPIQueryRepo[F] =
    new AccumulativeKPIQueryRepo[F] {
      override def implemented: Boolean = false

      def queries: F[List[PerUserSamplesQuery[F]]] = Nil.pure[F].widen
    }
}

trait PerUserSamplesQuery[F[_]]
    extends KPIEventQuery[F, QueryAccumulativeKPI, PerUserSamples] {
  def name: QueryName
  def params: Map[FieldName, DefaultParamValue] = Map.empty
  def frequency: FiniteDuration
}

object Query {
  type DefaultParamValue = Option[String]
}
