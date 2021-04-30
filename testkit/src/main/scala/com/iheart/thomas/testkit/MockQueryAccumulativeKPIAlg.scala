package com.iheart.thomas.testkit

import cats.Applicative
import cats.effect.IO
import cats.implicits._
import com.iheart.thomas.analysis.KPIEventQuery.PerUserSamplesQuery
import com.iheart.thomas.analysis.{
  KPI,
  KPIEventQuery,
  KPIName,
  PerUserSamples,
  QueryAccumulativeKPIAlg,
  QuerySpec
}
import com.iheart.thomas.{ArmName, FeatureName}

import java.time.Instant

object MockQueryAccumulativeKPIAlg {
  implicit val nullAlg = QueryAccumulativeKPIAlg.unsupported[IO]
  type MockData[E] = (FeatureName, ArmName, KPIName, Instant, Instant, E)
  def apply[F[_]: Applicative](
      data: List[MockData[PerUserSamples]],
      querySpecs: List[QuerySpec] = Nil
    ): QueryAccumulativeKPIAlg[F] =
    new QueryAccumulativeKPIAlg[F] {

      def eventQuery: PerUserSamplesQuery[F] = mockQuery(data)
      def availableQuerySpecs: F[List[QuerySpec]] = querySpecs.pure[F]
    }

  def mockQuery[F[_]: Applicative, K <: KPI, E](
      data: List[MockData[E]]
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
