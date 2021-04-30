package com.iheart.thomas.testkit

import cats.Applicative
import cats.effect.IO
import com.iheart.thomas.{ArmName, FeatureName}
import com.iheart.thomas.analysis.{
  KPI,
  KPIName,
  PerUserSamples,
  QueryAccumulativeKPI
}
import com.iheart.thomas.stream.KPIEventQuery
import cats.implicits._

import java.time.Instant

object MockEventQuery {
  implicit val failingEventQuery
      : KPIEventQuery[IO, QueryAccumulativeKPI, PerUserSamples] =
    KPIEventQuery.alwaysFail[IO, QueryAccumulativeKPI, PerUserSamples]

  type MockData[E] = (FeatureName, ArmName, KPIName, Instant, Instant, E)
  def apply[F[_]: Applicative, K <: KPI, E](
      data: List[(FeatureName, ArmName, KPIName, Instant, Instant, E)]
    ): KPIEventQuery[F, K, E] =
    new KPIEventQuery[F, K, E] {

      def find(
          k: K,
          at: Instant
        ): List[(FeatureName, ArmName, E)] =
        data
          .collect {
            case (fn, am, kpiName, start, end, data)
                if kpiName == k.name && start.isBefore(at) && end.isAfter(at) =>
              (fn, am, data)
          }
      def apply(
          k: K,
          at: Instant
        ): F[List[E]] =
        find(k, at).map(_._3).pure[F]

      def apply(
          k: K,
          feature: FeatureName,
          at: Instant
        ): F[List[(ArmName, E)]] =
        find(k, at)
          .collect {
            case (fn, am, data) if (fn == feature) => (am, data)
          }
          .pure[F]
    }
}
