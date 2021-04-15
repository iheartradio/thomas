package com.iheart.thomas.analysis.bayesian

import com.iheart.thomas.analysis.`package`.Indicator
import com.iheart.thomas.analysis.bayesian.models.BetaModel
import com.iheart.thomas.analysis.Conversions
import com.stripe.rainier.core.Beta

trait KPIIndicator[Model, Measurement] {
  def apply(
      model: Model,
      data: Measurement
    ): Indicator
}

object KPIIndicator {
  implicit val betaConversion: KPIIndicator[BetaModel, Conversions] =
    (model: BetaModel, data: Conversions) => {
      val postAlpha = model.alphaPrior + data.converted
      val postBeta = model.betaPrior + data.total - data.converted
      Variable(Beta(postAlpha, postBeta).latent, None)
    }
}
