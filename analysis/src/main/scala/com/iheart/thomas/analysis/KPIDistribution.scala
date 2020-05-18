package com.iheart.thomas
package analysis

import java.time.Instant

import cats.MonadError
import com.iheart.thomas.abtest.json.play.Formats.j
import com.iheart.thomas.analysis.AssessmentAlg.{
  BayesianAssessmentAlg,
  BayesianBasicAssessmentAlg
}
import com.iheart.thomas.analysis.DistributionSpec.{Normal, Uniform}
import com.stripe.rainier.compute.Real
import com.stripe.rainier.core._
import com.stripe.rainier.sampler.{RNG, Sampler}
import io.estatico.newtype.Coercible
import monocle.macros.syntax.lens._
import org.apache.commons.math3.distribution.GammaDistribution
import org.apache.commons.math3.stat.inference.KolmogorovSmirnovTest
import _root_.play.api.libs.json._
import cats.effect.Sync
import cats.implicits._

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

  implicit val bkpidFormat: Format[BetaKPIDistribution] =
    j.format[BetaKPIDistribution]
}

case class BetaKPIDistribution(
    name: KPIName,
    alphaPrior: Double,
    betaPrior: Double)
    extends KPIDistribution {
  def updateFrom(conversions: Conversions): BetaKPIDistribution =
    copy(
      alphaPrior = conversions.converted + 1d,
      betaPrior = conversions.total - conversions.converted + 1d
    )
}

object BetaKPIDistribution {

  def sample(
      b: BetaKPIDistribution,
      data: Conversions
    ): Indicator = {
    val postAlpha = b.alphaPrior + data.converted
    val postBeta = b.betaPrior + data.total - data.converted
    Variable(Beta(postAlpha, postBeta).latent, None)
  }

  implicit def betaInstances[F[_]](
      implicit
      sampler: Sampler,
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
          start: Instant,
          end: Instant
        ): F[(BetaKPIDistribution, Double)] =
        B.measureHistory(kpi, start, end).map { conversions =>
          (
            kpi.updateFrom(conversions),
            0d
          )
        }
    }

  implicit def basicAssessmentAlg[F[_]](
      implicit
      sampler: Sampler,
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

//todo: remove this flawed model once LogNormal is ready for prime time.
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
      sampler: Sampler,
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
        ): Variable[(Real, Real)] = {
        val shape = gk.shapePrior.distribution.latent
        val scale = gk.scalePrior.distribution.latent
        val g = Model.observe(data, Gamma(shape, scale))
        Variable((shape, scale), g)
      }

      def sampleIndicator(
          gk: GammaKPIDistribution,
          data: List[Double]
        ): Indicator = {
        fitModel(gk, data).map {
          case (shape, scale) => shape * scale
        }
      }

      def updateFromData(
          k: GammaKPIDistribution,
          start: Instant,
          end: Instant
        ): F[(GammaKPIDistribution, Double)] =
        K.measureHistory(k, start, end).map { data =>
          val model = fitModel(k, data)

          val shapeSample = model.map(_._1).predict()
          val scaleSample = model.map(_._2).predict()

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

/**
  * https://stats.stackexchange.com/questions/30369/priors-for-log-normal-models
  * @param name
  * @param locationPrior
  * @param scaleLnPrior
  */
case class LogNormalDistribution(
    name: KPIName,
    locationPrior: Normal,
    scaleLnPrior: Uniform)

object LogNormalDistribution {

  implicit def logNormalInstances[F[_]](
      implicit
      sampler: Sampler,
      rng: RNG,
      K: Measurable[F, Measurements, LogNormalDistribution],
      F: MonadError[F, Throwable]
    ): AssessmentAlg[F, LogNormalDistribution]
    with UpdatableKPI[F, LogNormalDistribution] =
    new BayesianAssessmentAlg[F, LogNormalDistribution, Measurements]
    with UpdatableKPI[F, LogNormalDistribution] {

      private def fitModel(
          logNormal: LogNormalDistribution,
          data: List[Double]
        ): Variable[(Real, Real)] = {
        val location = logNormal.locationPrior.distribution.latent
        val scale = logNormal.scaleLnPrior.distribution.latent.exp
        val g = Model.observe(data, LogNormal(location, scale))
        Variable((location, scale), g)
      }

      def sampleIndicator(
          logNormalDistribution: LogNormalDistribution,
          data: List[Double]
        ): Indicator = {
        fitModel(logNormalDistribution, data).map {
          case (location, scale) => (location + scale.pow(2d) / 2d).exp
        }
      }

      def updateFromData(
          k: LogNormalDistribution,
          start: Instant,
          end: Instant
        ): F[(LogNormalDistribution, Double)] =
        K.measureHistory(k, start, end).map { data =>
          val model = fitModel(k, data)

          val (locationSample, scaleSample) =
            model.predict().foldLeft((List.empty[Double], List.empty[Double])) {
              (memo, p) =>
                (p._1 :: memo._1, p._2 :: memo._2)
            }

          val updated = k.copy(
            locationPrior = Normal.fit(locationSample),
            scaleLnPrior = Uniform(
              0,
              Math.log(
                scaleSample.maximumOption.map(_ * 2).getOrElse(k.scaleLnPrior.to)
              )
            )
          )

          val ksTest = new KolmogorovSmirnovTest()
          val gd = new org.apache.commons.math3.distribution.LogNormalDistribution(
            updated.locationPrior.location,
            scaleSample.sum / scaleSample.size.toDouble
          )
          val ksStatistics = ksTest.kolmogorovSmirnovStatistic(gd, data.toArray)

          (updated, ksStatistics)
        }
    }
}
