package com.iheart.thomas.analysis
package bayesian

import breeze.stats.meanAndVariance.MeanAndVariance
import com.iheart.thomas.analysis.bayesian.models.{
  BetaModel,
  LogNormalModel,
  NormalModel
}
import com.iheart.thomas.analysis.{Conversions, PerUserSamples}
import henkan.convert.Syntax._

trait Posterior[Model, Measurement] {
  def apply(
      model: Model,
      data: Measurement
    ): Model
}

object Posterior {

  def update[Model, Measurement](
      model: Model,
      measurement: Measurement
    )(implicit
      posterior: Posterior[Model, Measurement]
    ): Model = posterior(model, measurement)

  implicit def conversionsPosterior: Posterior[ConversionKPI, Conversions] =
    (k: ConversionKPI, data: Conversions) => k.copy(model = update(k.model, data))

  implicit def accumulativePosterior
      : Posterior[QueryAccumulativeKPI, PerUserSamplesLnSummary] =
    (k: QueryAccumulativeKPI, data: PerUserSamplesLnSummary) =>
      k.copy(model = update(k.model, data))

  implicit val betaConversion: Posterior[BetaModel, Conversions] =
    (model: BetaModel, data: Conversions) => {
      val postAlpha = model.alpha + data.converted
      val postBeta = model.beta + data.total - data.converted
      BetaModel(postAlpha, postBeta)
    }

  /**
    * Ref: https://people.eecs.berkeley.edu/~jordan/courses/260-spring10/lectures/lecture5.pdf
    */
  implicit val normalSamples: Posterior[NormalModel, MeanAndVariance] =
    (model: NormalModel, data: MeanAndVariance) => {
      import model._
      val n = data.count.toDouble
      NormalModel(
        miu0 = ((n0 * miu0) + (n * data.mean)) / (n + n0),
        n0 = n0 + n,
        alpha = alpha + (n / 2d),
        beta = beta + (data.variance * (n - 1d) / 2d) +
          (n * model.n0) * Math.pow(data.mean - miu0, 2) / (2 * (n + n0))
      )
    }

  implicit val logNormalSamplesSummary
      : Posterior[LogNormalModel, PerUserSamples.LnSummary] =
    (model: LogNormalModel, data: PerUserSamples.LnSummary) => {
      LogNormalModel(update(model.inner, data.to[MeanAndVariance]()))
    }
}
