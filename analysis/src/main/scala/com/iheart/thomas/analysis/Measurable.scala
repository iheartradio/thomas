package com.iheart.thomas
package analysis


import com.iheart.thomas.model.GroupName
import com.stripe.rainier.compute.Real
import com.stripe.rainier.core.{Continuous, Gamma, RandomVariable}
import com.stripe.rainier.sampler.{RNG}
import simulacrum._
import cats.implicits._
import com.iheart.thomas.analysis.DistributionSpec.Normal

import scala.util.control.NoStackTrace
import org.apache.commons.math3.stat.inference.KolmogorovSmirnovTest
import org.apache.commons.math3.distribution.GammaDistribution
import analysis.syntax.all._

@typeclass
trait Measurable[K] {
  def assess(k: K,
             groupMeasurements: Map[GroupName, Measurements],
             control: Measurements): Map[GroupName, NumericGroupResult]

  def assess(k: K, allMeasurements: Map[GroupName, Measurements],
             controlGroupName: GroupName): Either[Measurable.ControlGroupMeasurementMissing.type, Map[GroupName, NumericGroupResult]]
    = allMeasurements.get(controlGroupName).
        toRight(Measurable.ControlGroupMeasurementMissing).
        map(assess(k, allMeasurements.filterKeys(_ =!= controlGroupName), _))

  def updatePrior(k: K, historicalData: Measurements, scale: Option[Double] = None): K

  /**
   * @return Kolmogorov-Smirnov statistics
   */
  def kSTest(kpi: K, data: Measurements): Double

  def update(kpi: K, data: Measurements, scale: Option[Double]): (K, Double) = {
    val updatedK = updatePrior(kpi, data, scale)
    (updatedK, kSTest(updatedK, data))
  }
}

object Measurable {
  case object ControlGroupMeasurementMissing extends RuntimeException with NoStackTrace

  abstract class BayesianMeasurable[K](implicit
                                       samplerSettings: SampleSettings,
                                       rng: RNG
                                      ) extends Measurable[K] {
    def measure(k: K, data: Measurements): Indicator

    def assess(k: K,
               groupMeasurements: Map[GroupName, Measurements],
               control: Measurements): Map[GroupName, NumericGroupResult] = {
      groupMeasurements.map {
        case (gn, ms) =>
          import samplerSettings._
          val improvement = (for {
            treatmentIndicator <- measure(k, ms)
            controlIndicator <- measure(k, control)
          } yield treatmentIndicator - controlIndicator).sample(sampler, warmupIterations, iterations, keepEvery)

          (gn, NumericGroupResult(improvement))
      }
    }
  }

  implicit def gammaKPIMeasurable(implicit
                                  sampleSettings: SampleSettings,
                                  rng: RNG) : Measurable[GammaKPI] =
    new BayesianMeasurable[GammaKPI] {

      private def fitModel(gk: GammaKPI, data: List[Double]): RandomVariable[(Real, Real, Continuous)] =
        for {
          shape <- gk.shapePrior.distribution.param
          scale <- gk.scalePrior.distribution.param
          g <- Gamma(shape, scale).fit(data)
        } yield (shape, scale, g)

      def measure(gk: GammaKPI, data: List[Double]): RandomVariable[Real] =
        fitModel(gk, data).map {
          case (shape, scale, _) => shape * scale
        }

      def updatePrior(k: GammaKPI, historicalData: Measurements, scale: Option[Double] = None): GammaKPI = {
        val model = fitModel(k, historicalData)

        val shapeSample = model.map(_._1).sample(sampleSettings)
        val scaleSample = model.map(_._2).sample(sampleSettings)

        val updated = k.copy(shapePrior = Normal.fit(shapeSample), scalePrior = Normal.fit(scaleSample))
        scale.fold(updated)(updated.scalePriors)
      }

      def kSTest(kpi: GammaKPI, data: Measurements): Double = {
         val ksTest = new KolmogorovSmirnovTest()
        val gd = new GammaDistribution(kpi.shapePrior.location, kpi.scalePrior.location)
        ksTest.kolmogorovSmirnovStatistic(gd, data.toArray)
      }
    }

}
