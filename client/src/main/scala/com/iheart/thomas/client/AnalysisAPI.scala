package com.iheart.thomas
package client

import java.time.OffsetDateTime

import cats.MonadError
import com.iheart.thomas.analysis._
import com.iheart.thomas.model.{FeatureName, GroupName}
import analysis.implicits._
import com.stripe.rainier.sampler.RNG
import cats.implicits._

import scala.util.control.NoStackTrace

trait AnalysisAPI[F[_]] {
  def updateKPI(name: KPIName,
                start: OffsetDateTime,
                end: OffsetDateTime): F[(KPIDistribution, Double)]

  def initKPI(kpi: KPIDistribution): F[KPIDistribution]

  def assess(feature: FeatureName,
             kpi: KPIName,
             baseline: GroupName): F[Map[GroupName, NumericGroupResult]]
}



object AnalysisAPI {
  implicit def default[F[_]](
    implicit
      G: Measurable[F, GammaKPIDistribution],
      sampleSettings: SampleSettings,
      client: Client[F],
      F: MonadError[F, Throwable]): AnalysisAPI[F] = new AnalysisAPI[F] {

    implicit val rng: RNG = RNG.default


    def updateKPI(name: KPIName, start: OffsetDateTime, end: OffsetDateTime): F[(KPIDistribution, Double)] =
      for {
        kpi  <- client.getKPI(name.n)
        p    <- UpdatableKPI[F, KPIDistribution].updateFromData(kpi, start, end)
        (updated, score) = p
        stored   <- client.updateKPI(updated)
      } yield (stored, score)


    def assess(feature: FeatureName, kpi: KPIName, baseline: GroupName): F[Map[GroupName, NumericGroupResult]] =
      for {
        kpi  <- client.getKPI(kpi.n)
        abtestO <- client.test(feature)
        abtest <- abtestO.liftTo[F](AbtestNotFound)
        r <- kpi.assess(abtest.data, baseline)
      } yield r

    def initKPI(kpi: KPIDistribution): F[KPIDistribution] = client.updateKPI(kpi)
  }

  case object AbtestNotFound extends RuntimeException with NoStackTrace
}
