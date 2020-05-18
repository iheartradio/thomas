package com.iheart.thomas
package client

import java.time.Instant

import cats.MonadError
import com.iheart.thomas.analysis._
import analysis.implicits._
import com.stripe.rainier.sampler.{RNG, Sampler}
import cats.implicits._
import com.iheart.thomas.abtest.Error.NotFound

import scala.util.control.NoStackTrace

trait AnalysisAPI[F[_], K <: KPIModel] {
  def updateKPI(
      name: KPIName,
      start: Instant,
      end: Instant
    ): F[(K, Double)]

  def saveKPI(kpi: K): F[K]

  def assess(
      feature: FeatureName,
      kpi: KPIName,
      baseline: GroupName,
      start: Option[Instant] = None,
      end: Option[Instant] = None
    ): F[Map[GroupName, NumericGroupResult]]

  def updateOrInitKPI(
      name: KPIName,
      start: Instant,
      end: Instant,
      init: => K
    )(implicit F: MonadError[F, Throwable]
    ): F[(K, Double)] = {
    updateKPI(name, start, end).recoverWith {
      case NotFound(_) => saveKPI(init).flatMap(k => updateKPI(k.name, start, end))
    }
  }
}

object AnalysisAPI {

  abstract class AnalysisAPIWithClient[F[_], K <: KPIModel](
      implicit UK: UpdatableKPI[F, K],
      abtestKPI: AssessmentAlg[F, K],
      client: AbtestClient[F],
      F: MonadError[F, Throwable])
      extends AnalysisAPI[F, K] {

    def validateKPIType(k: KPIModel): F[K] =
      narrowToK.lift.apply(k).liftTo[F](KPINotFound)

    def narrowToK: PartialFunction[KPIModel, K]

    def updateKPI(
        name: KPIName,
        start: Instant,
        end: Instant
      ): F[(K, Double)] = {
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
        start: Option[Instant] = None,
        end: Option[Instant] = None
      ): F[Map[GroupName, NumericGroupResult]] =
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
      G: Measurable[F, Measurements, GammaKPIModel],
      sampler: Sampler = Sampler.default,
      rng: RNG = RNG.default,
      client: AbtestClient[F],
      F: MonadError[F, Throwable]
    ): AnalysisAPI[F, GammaKPIModel] =
    new AnalysisAPIWithClient[F, GammaKPIModel] {
      def narrowToK: PartialFunction[KPIModel, GammaKPIModel] = {
        case g: GammaKPIModel => g
      }
    }

  implicit def defaultBeta[F[_]](
      implicit
      G: Measurable[F, Conversions, BetaKPIModel],
      sampler: Sampler = Sampler.default,
      rng: RNG = RNG.default,
      client: AbtestClient[F],
      F: MonadError[F, Throwable]
    ): AnalysisAPI[F, BetaKPIModel] =
    new AnalysisAPIWithClient[F, BetaKPIModel] {
      def narrowToK: PartialFunction[KPIModel, BetaKPIModel] = {
        case b: BetaKPIModel => b
      }
    }

  case object AbtestNotFound extends RuntimeException with NoStackTrace
  case object KPINotFound extends RuntimeException with NoStackTrace
}
