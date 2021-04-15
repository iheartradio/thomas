package com.iheart.thomas.analysis
package bayesian

import com.iheart.thomas.analysis.bayesian.models.{BetaModel, NormalModel}
import com.stripe.rainier.core.{Beta, Gamma, Normal}

trait KPIIndicator[Model] {
  def apply(
      model: Model
    ): Indicator
}

object KPIIndicator {

  implicit val betaInstance: KPIIndicator[BetaModel] =
    (model: BetaModel) => Variable(Beta(model.alpha, model.beta).latent)

  implicit val normalInstance: KPIIndicator[NormalModel] =
    (model: NormalModel) => {
      import model._
      val precision = Gamma(shape = alpha, scale = 1d / beta)
      Variable(Normal(miu0, precision.latent * n0).latent)
    }
}
