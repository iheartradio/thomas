package com.iheart.thomas.analysis


import com.iheart.thomas.model.GroupName
import com.stripe.rainier.compute.Real
import com.stripe.rainier.core.{Gamma, RandomVariable, Sampleable}
import com.stripe.rainier.sampler.{RNG, Sampler, Walkers}
import simulacrum._

@typeclass
trait Measurable[K] {
  def assess(k: K,
             groupMeasurements: Map[GroupName, Measurements],
             control: Measurements): Map[GroupName, GroupResult]
}

object Measurable {

  abstract class BayesianMeasurable[K](implicit
                                       samplerSettings: SamplerSettings,
                                       rng: RNG,
                                       sampleable: Sampleable[Real, Double]) extends Measurable[K] {
    def measure(k: K, data: Measurements): Indicator

    def assess(k: K,
               groupMeasurements: Map[GroupName, Measurements],
               control: Measurements): Map[GroupName, GroupResult] = {

      def findMinimum(data: List[Double], threshold: Double): Double =
        data.sorted.take((data.size.toDouble * (1.0 - threshold)).toInt).last


      groupMeasurements.map {
        case (gn, ms) =>
          import samplerSettings._
          val improvement = (for {
            treatmentIndicator <- measure(k, ms)
            controlIndicator <- measure(k, control)
          } yield treatmentIndicator - controlIndicator).sample(sampler, warmupIterations, iterations, keepEvery)

          val possibility = Probability(improvement.count(_ > 0).toDouble / improvement.length)
          val cost = findMinimum(improvement, 0.95)
          val expected = improvement.sum / improvement.size
          (gn, GroupResult(possibility, KPIDouble(cost), KPIDouble(expected), improvement.map(KPIDouble(_))))
      }
    }
  }

  implicit def gammaKPIMeasurable(implicit
                                   samplerSettings: SamplerSettings,
                                   rng: RNG,
                                   sampleable: Sampleable[Real, Double]) : Measurable[GammaKPI] =
    new BayesianMeasurable[GammaKPI] {
      def measure(gk: GammaKPI, data: List[Double]): RandomVariable[Real] =
        for {
          shape <- gk.shapePrior.distribution.param
          scale <- gk.scalePrior.distribution.param
          _ <- Gamma(shape, scale).fit(data)
        } yield shape * scale
    }


  case class SamplerSettings(
                              sampler: Sampler,
                              warmupIterations: Int,
                              iterations: Int,
                              keepEvery: Int = 1
                            )

  object SamplerSettings {
    val default = SamplerSettings(Walkers(100), 5000, 10000)
  }
}
