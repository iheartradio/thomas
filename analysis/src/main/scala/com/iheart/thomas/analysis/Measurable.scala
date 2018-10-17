package com.iheart.thomas.analysis


import com.iheart.thomas.model.GroupName
import com.stripe.rainier.compute.Real
import com.stripe.rainier.core.{Gamma, RandomVariable, Sampleable}
import com.stripe.rainier.sampler.{RNG, Sampler, Walkers}
import simulacrum._
import cats.implicits._

import scala.util.control.NoStackTrace


@typeclass
trait Measurable[K] {
  def assess(k: K,
             groupMeasurements: Map[GroupName, Measurements],
             control: Measurements): Map[GroupName, GroupResult]

  def assess(k: K, allMeasurements: Map[GroupName, Measurements],
             controlGroupName: GroupName): Either[Measurable.ControlGroupMeasurementMissing.type, Map[GroupName, GroupResult]]
    = allMeasurements.get(controlGroupName).
        toRight(Measurable.ControlGroupMeasurementMissing).
        map(assess(k, allMeasurements.filterKeys(_ =!= controlGroupName), _))
}

object Measurable {
  case object ControlGroupMeasurementMissing extends RuntimeException with NoStackTrace

  abstract class BayesianMeasurable[K](implicit
                                       samplerSettings: SamplerSettings,
                                       rng: RNG,
                                       sampleable: Sampleable[Real, Double]) extends Measurable[K] {
    def measure(k: K, data: Measurements): Indicator

    def assess(k: K,
               groupMeasurements: Map[GroupName, Measurements],
               control: Measurements): Map[GroupName, GroupResult] = {



      groupMeasurements.map {
        case (gn, ms) =>
          import samplerSettings._
          val improvement = (for {
            treatmentIndicator <- measure(k, ms)
            controlIndicator <- measure(k, control)
          } yield treatmentIndicator - controlIndicator).sample(sampler, warmupIterations, iterations, keepEvery)

          (gn, GroupResult(improvement))
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
    val default = SamplerSettings(Walkers(100), 10000, 20000)
  }
}
