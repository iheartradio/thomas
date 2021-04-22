package com.iheart.thomas.analysis
package bayesian

import com.iheart.thomas.analysis.bayesian.models.{
  BetaModel,
  LogNormalModel,
  NormalModel
}
import com.iheart.thomas.analysis.{Conversions, PerUserSamples}

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
      : Posterior[AccumulativeKPI, PerUserSamplesSummary] =
    (k: AccumulativeKPI, data: PerUserSamplesSummary) =>
      k.copy(model = update(k.model, data))

  implicit val betaConversion: Posterior[BetaModel, Conversions] =
    (model: BetaModel, data: Conversions) => {
      val postAlpha = model.alpha + data.converted
      val postBeta = model.beta + data.total - data.converted
      BetaModel(postAlpha, postBeta)
    }

  implicit val normalSamples: Posterior[NormalModel, PerUserSamples.Summary] =
    (model: NormalModel, data: PerUserSamples.Summary) => {
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

  implicit val logNormalSamples: Posterior[LogNormalModel, PerUserSamples] =
    (model: LogNormalModel, data: PerUserSamples) => {
      update(model, data.ln.summary)
    }

  implicit val logNormalSamplesSummary
      : Posterior[LogNormalModel, PerUserSamples.Summary] =
    (model: LogNormalModel, data: PerUserSamples.Summary) => {
      LogNormalModel(update(model.inner, data))
    }
}
