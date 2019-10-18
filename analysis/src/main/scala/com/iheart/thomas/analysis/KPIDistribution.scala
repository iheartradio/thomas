package com.iheart.thomas
package analysis

import java.time.OffsetDateTime

import cats.MonadError
import com.iheart.thomas.abtest.Formats.j
import com.iheart.thomas.analysis.AssessmentAlg.{
  BayesianAssessmentAlg,
  BayesianBasicAssessmentAlg
}
import com.iheart.thomas.analysis.DistributionSpec.Normal
import com.stripe.rainier.compute.Real
import com.stripe.rainier.core._
import com.stripe.rainier.sampler.RNG
import io.estatico.newtype.Coercible
import monocle.macros.syntax.lens._
import org.apache.commons.math3.distribution.GammaDistribution
import org.apache.commons.math3.stat.inference.KolmogorovSmirnovTest
import _root_.play.api.libs.json._
import cats.effect.Sync
import cats.implicits._
import implicits._

sealed trait KPIDistribution extends Serializable with Product {
  def name: KPIName
}

object KPIDistribution {
  import julienrf.json.derived

  implicit private val normalDistFormat: Format[Normal] = j.format[Normal]

  implicit private def coercibleFormat[A, B](
      implicit ev: Coercible[Format[A], Format[B]],
      A: Format[A]
    ): Format[B] = ev(A)

  implicit val mdFormat: Format[KPIDistribution] =
    derived.flat.oformat[KPIDistribution]((__ \ "type").format[String])

}

case class BetaKPIDistribution(
    name: KPIName,
    alphaPrior: Double,
    betaPrior: Double)
    extends KPIDistribution

object BetaKPIDistribution {
  def sample(
      b: BetaKPIDistribution,
      data: Conversions
    ): Indicator = {
    val postAlpha = b.alphaPrior + data.converted
    val postBeta = b.betaPrior + data.total - data.converted
    Beta(postAlpha, postBeta).param
  }

  implicit def betaInstances[F[_]](
      implicit
      sampleSettings: SampleSettings,
      rng: RNG,
      B: Measurable[F, Conversions, BetaKPIDistribution],
      F: MonadError[F, Throwable]
    ): AssessmentAlg[F, BetaKPIDistribution]
    with UpdatableKPI[F, BetaKPIDistribution] =
    new BayesianAssessmentAlg[F, BetaKPIDistribution, Conversions]
    with UpdatableKPI[F, BetaKPIDistribution] {

      protected def sampleIndicator(
          b: BetaKPIDistribution,
          data: Conversions
        ) =
        sample(b, data)

      def updateFromData(
          kpi: BetaKPIDistribution,
          start: OffsetDateTime,
          end: OffsetDateTime
        ): F[(BetaKPIDistribution, Double)] =
        B.measureHistory(kpi, start, end).map { conversions =>
          (
            kpi.copy(
              alphaPrior = conversions.converted + 1d,
              betaPrior = conversions.total - conversions.converted + 1d
            ),
            0d
          )
        }
    }

  implicit def basicAssessmentAlg[F[_]](
      implicit
      sampleSettings: SampleSettings,
      rng: RNG,
      F: Sync[F]
    ): BasicAssessmentAlg[F, BetaKPIDistribution, Conversions] =
    new BayesianBasicAssessmentAlg[F, BetaKPIDistribution, Conversions] {
      protected def sampleIndicator(
          b: BetaKPIDistribution,
          data: Conversions
        ) =
        sample(b, data)
    }
}

case class GammaKPIDistribution(
    name: KPIName,
    shapePrior: Normal,
    scalePrior: Normal)
    extends KPIDistribution {
  def scalePriors(by: Double): GammaKPIDistribution = {
    val g = this.lens(_.shapePrior.scale).modify(_ * by)
    g.lens(_.scalePrior.scale).modify(_ * by)
  }
}

object GammaKPIDistribution {

  implicit def gammaKPIInstances[F[_]](
      implicit
      sampleSettings: SampleSettings,
      rng: RNG,
      K: Measurable[F, Measurements, GammaKPIDistribution],
      F: MonadError[F, Throwable]
    ): AssessmentAlg[F, GammaKPIDistribution]
    with UpdatableKPI[F, GammaKPIDistribution] =
    new BayesianAssessmentAlg[F, GammaKPIDistribution, Measurements]
    with UpdatableKPI[F, GammaKPIDistribution] {

      private def fitModel(
          gk: GammaKPIDistribution,
          data: List[Double]
        ): RandomVariable[(Real, Real, Distribution[Double])] =
        for {
          shape <- gk.shapePrior.distribution.param
          scale <- gk.scalePrior.distribution.param
          g <- Gamma(shape, scale).fit(data)
        } yield (shape, scale, g)

      def sampleIndicator(
          gk: GammaKPIDistribution,
          data: List[Double]
        ): Indicator =
        fitModel(gk, data).map {
          case (shape, scale, _) => shape * scale
        }

      def updateFromData(
          k: GammaKPIDistribution,
          start: OffsetDateTime,
          end: OffsetDateTime
        ): F[(GammaKPIDistribution, Double)] =
        K.measureHistory(k, start, end).map { data =>
          val model = fitModel(k, data)

          val shapeSample = model.map(_._1).sample(sampleSettings)
          val scaleSample = model.map(_._2).sample(sampleSettings)

          val updated = k.copy(
            shapePrior = Normal.fit(shapeSample),
            scalePrior = Normal.fit(scaleSample)
          )

          val ksTest = new KolmogorovSmirnovTest()
          val gd = new GammaDistribution(
            updated.shapePrior.location,
            updated.scalePrior.location
          )
          val ksStatistics = ksTest.kolmogorovSmirnovStatistic(gd, data.toArray)

          (updated, ksStatistics)
        }

    }
}
