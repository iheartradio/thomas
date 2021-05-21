package com.iheart.thomas.testkit

import cats.Applicative
import cats.effect.IO
import cats.implicits._
import com.iheart.thomas.analysis.{
  KPI,
  KPIEventQuery,
  KPIName,
  PerUserSamples,
  PerUserSamplesQuery,
  QueryAccumulativeKPI,
  AccumulativeKPIQueryRepo,
  QueryName
}
import com.iheart.thomas.{ArmName, FeatureName}

import java.time.Instant
import scala.concurrent.duration._

object MockQueryAccumulativeKPIAlg {
  implicit val nullAlg = AccumulativeKPIQueryRepo.unsupported[IO]
  type MockData[E] = (FeatureName, ArmName, KPIName, Instant, Instant, E)
  val mockQueryName = QueryName("Mock Query with preset data")
  def mockLogNormalData(kpiName: KPIName) = {
    val dist = breeze.stats.distributions.LogNormal(1d, 0.3d)
    val begin = Instant.now.minusSeconds(20000)
    val end = Instant.now.plusSeconds(20000)
    List(
      (
        "A_Feature",
        "A",
        kpiName,
        begin,
        end,
        PerUserSamples(dist.sample(5000).toArray)
      ),
      (
        "A_Feature",
        "B",
        kpiName,
        begin,
        end,
        PerUserSamples(dist.sample(5000).toArray)
      )
    )
  }

  def apply[F[_]: Applicative](
      data: List[MockData[PerUserSamples]] =
        mockLogNormalData(KPIName("testAccumulativeKPI")),
      freq: FiniteDuration = 500.millis
    ): AccumulativeKPIQueryRepo[F] =
    new AccumulativeKPIQueryRepo[F] {

      def queries: F[List[PerUserSamplesQuery[F]]] =
        List(
          new MockQuery[F, QueryAccumulativeKPI, PerUserSamples](data)
            with PerUserSamplesQuery[F] {
            val name: QueryName = mockQueryName

            def frequency: FiniteDuration = freq
          }
        ).pure[F].widen
    }

  abstract class MockQuery[F[_]: Applicative, K <: KPI, E](
      data: List[MockData[E]])
      extends KPIEventQuery[F, K, E] {

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
      ): F[Map[ArmName, E]] = {
      find(k, at)
        .collect {
          case (fn, am, data) if (fn == feature) => (am, data)
        }
        .toMap
        .pure[F]
    }
  }
}
