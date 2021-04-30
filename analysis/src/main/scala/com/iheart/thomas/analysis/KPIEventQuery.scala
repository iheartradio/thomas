package com.iheart.thomas.analysis

import cats.ApplicativeThrow
import com.iheart.thomas.{ArmName, FeatureName}

import java.time.Instant
import scala.annotation.implicitAmbiguous
import scala.util.control.NoStackTrace

@implicitAmbiguous(
  "Query $Event for $K. If you don't need this. Use KPIEventQuery.alwaysFail"
)
trait KPIEventQuery[F[_], K <: KPI, Event] {
  def apply(
      k: K,
      at: Instant
    ): F[List[Event]]

  def apply(
      k: K,
      feature: FeatureName,
      at: Instant
    ): F[List[(ArmName, Event)]]
}

object KPIEventQuery {
  type PerUserSamplesQuery[F[_]] =
    KPIEventQuery[F, QueryAccumulativeKPI, PerUserSamples]

  case class KPIEventQueryNotImplemented(typeName: String)
      extends RuntimeException
      with NoStackTrace {
    override def getMessage: String =
      s"KPIEventQuery was not implemented for $typeName"
  }

  def alwaysFail[F[_], K <: KPI, E](
      implicit F: ApplicativeThrow[F]
    ): KPIEventQuery[F, K, E] =
    new KPIEventQuery[F, K, E] {
      def apply(
          k: K,
          at: Instant
        ): F[List[E]] =
        F.raiseError(KPIEventQueryNotImplemented(k.getClass.getTypeName))

      override def apply(
          k: K,
          feature: FeatureName,
          at: Instant
        ): F[List[(ArmName, E)]] =
        F.raiseError(KPIEventQueryNotImplemented(k.getClass.getTypeName))
    }
}
