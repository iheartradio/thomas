package com.iheart.thomas.analysis
package bayesian.fit

import cats.MonadError
import FitAssessmentAlg.BayesianAssessmentAlg
import DistributionSpec.{Normal, Uniform}

import com.stripe.rainier.compute.Real
import com.stripe.rainier.core.{LogNormal, Model}
import com.stripe.rainier.sampler.{RNG, SamplerConfig}
import org.apache.commons.math3.stat.inference.KolmogorovSmirnovTest
import cats.implicits._
import com.iheart.thomas.analysis.bayesian.Variable
import java.time.Instant

/**
  * https://stats.stackexchange.com/questions/30369/priors-for-log-normal-models
  *
 * @param name
  * @param locationPrior
  * @param scaleLnPrior
  */
case class LogNormalFit(
    locationPrior: Normal,
    scaleLnPrior: Uniform)

object LogNormalFit {
  type Measurements = List[Double]
  implicit def logNormalInstances[F[_]](
      implicit
      sampler: SamplerConfig = SamplerConfig.default,
      rng: RNG = RNG.default,
      K: Measurable[F, Measurements, LogNormalFit],
      F: MonadError[F, Throwable]
    ): FitAssessmentAlg[F, LogNormalFit] with UpdatableKPI[F, LogNormalFit] =
    new BayesianAssessmentAlg[F, LogNormalFit, Measurements]
      with UpdatableKPI[F, LogNormalFit] {

      private def fitModel(
          logNormal: LogNormalFit,
          data: List[Double]
        ): Variable[(Real, Real)] = {
        val location = logNormal.locationPrior.distribution.latent
        val scale = logNormal.scaleLnPrior.distribution.latent.exp
        val g = Model.observe(data, LogNormal(location, scale))
        Variable((location, scale), g)
      }

      def sampleIndicator(
          logNormalDistribution: LogNormalFit,
          data: List[Double]
        ): Indicator = {
        fitModel(logNormalDistribution, data).map {
          case (location, scale) => (location + scale.pow(2d) / 2d).exp
        }
      }

      def updateFromData(
          k: LogNormalFit,
          start: Instant,
          end: Instant
        ): F[(LogNormalFit, Double)] =
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
              Math.log(
                scaleSample.minimumOption.map(_ / 10).getOrElse(k.scaleLnPrior.from)
              ),
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
