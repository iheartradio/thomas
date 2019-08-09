package com.iheart.thomas
package client

import java.time.OffsetDateTime

import cats.MonadError
import com.iheart.thomas.analysis._
import analysis.implicits._
import com.stripe.rainier.sampler.RNG
import cats.implicits._
import com.iheart.thomas.abtest.Error.NotFound

import scala.util.control.NoStackTrace

trait AnalysisAPI[F[_], K <: KPIDistribution] {
  def updateKPI(name: KPIName, start: OffsetDateTime, end: OffsetDateTime): F[(K, Double)]

  def saveKPI(kpi: K): F[K]

  def assess(feature: FeatureName,
             kpi: KPIName,
             baseline: GroupName,
             start: Option[OffsetDateTime] = None,
             end: Option[OffsetDateTime] = None): F[Map[GroupName, NumericGroupResult]]

  def updateOrInitKPI(
      name: KPIName,
      start: OffsetDateTime,
      end: OffsetDateTime,
      init: => K)(implicit F: MonadError[F, Throwable]): F[(K, Double)] = {
    updateKPI(name, start, end).recoverWith {
      case NotFound(_) => saveKPI(init).flatMap(k => updateKPI(k.name, start, end))
    }
  }
}

object AnalysisAPI {

  abstract class AnalysisAPIWithClient[F[_], K <: KPIDistribution](
      implicit UK: UpdatableKPI[F, K],
      abtestKPI: AssessmentAlg[F, K],
      client: Client[F],
      F: MonadError[F, Throwable])
      extends AnalysisAPI[F, K] {

    def validateKPIType(k: KPIDistribution): F[K] =
      narrowToK.lift.apply(k).liftTo[F](KPINotFound)

    def narrowToK: PartialFunction[KPIDistribution, K]

    def updateKPI(name: KPIName,
                  start: OffsetDateTime,
                  end: OffsetDateTime): F[(K, Double)] = {
      for {
        kpi <- client.getKPI(name.n).flatMap(validateKPIType)
        p <- kpi.updateFromData[F](start, end)
        (updated, score) = p
        stored <- client.saveKPI(updated).flatMap(validateKPIType)
      } yield (stored, score)
    }

    def assess(
        feature: FeatureName,
        kpi: KPIName,
        baseline: GroupName,
        start: Option[OffsetDateTime] = None,
        end: Option[OffsetDateTime] = None): F[Map[GroupName, NumericGroupResult]] =
      for {
        kpi <- client.getKPI(kpi.n).flatMap(validateKPIType)
        abtestO <- client.test(feature, start)
        abtest <- abtestO.liftTo[F](AbtestNotFound)
        r <- kpi.assess(abtest.data, baseline, start, end)
      } yield r

    def saveKPI(kpi: K): F[K] = client.saveKPI(kpi).flatMap(validateKPIType)
  }

  implicit def defaultGamma[F[_]](
      implicit
      G: Measurable[F, Measurements, GammaKPIDistribution],
      sampleSettings: SampleSettings = SampleSettings.default,
      rng: RNG = RNG.default,
      client: Client[F],
      F: MonadError[F, Throwable]): AnalysisAPI[F, GammaKPIDistribution] =
    new AnalysisAPIWithClient[F, GammaKPIDistribution] {
      def narrowToK: PartialFunction[KPIDistribution, GammaKPIDistribution] = {
        case g: GammaKPIDistribution => g
      }
    }

  implicit def defaultBeta[F[_]](
      implicit
      G: Measurable[F, Conversions, BetaKPIDistribution],
      sampleSettings: SampleSettings = SampleSettings.default,
      rng: RNG = RNG.default,
      client: Client[F],
      F: MonadError[F, Throwable]): AnalysisAPI[F, BetaKPIDistribution] =
    new AnalysisAPIWithClient[F, BetaKPIDistribution] {
      def narrowToK: PartialFunction[KPIDistribution, BetaKPIDistribution] = {
        case b: BetaKPIDistribution => b
      }
    }

  case object AbtestNotFound extends RuntimeException with NoStackTrace
  case object KPINotFound extends RuntimeException with NoStackTrace
}
