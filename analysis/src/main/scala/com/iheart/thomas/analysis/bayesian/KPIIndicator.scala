package com.iheart.thomas.analysis
package bayesian

import com.iheart.thomas.analysis.bayesian.models.{
  BetaModel,
  LogNormalModel,
  NormalModel
}
import com.stripe.rainier.sampler.{RNG, SamplerConfig}

trait KPIIndicator[Model] {
  def apply(
      model: Model
    ): Indicator
}

object KPIIndicator {
  def sample[Model](
      model: Model
    )(implicit indicator: KPIIndicator[Model],
      sampler: SamplerConfig,
      rng: RNG
    ): Seq[Double] = indicator(model).predict()

  implicit val betaInstance: KPIIndicator[BetaModel] =
    (model: BetaModel) => Variable(model.prediction)

  implicit val normalInstance: KPIIndicator[NormalModel] =
    (model: NormalModel) => Variable(model.mean)

  implicit val logNormalInstance: KPIIndicator[LogNormalModel] =
    (model: LogNormalModel) => Variable(model.mean)
}
