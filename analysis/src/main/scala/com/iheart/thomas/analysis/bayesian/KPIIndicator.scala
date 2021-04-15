package com.iheart.thomas.analysis
package bayesian

import com.iheart.thomas.analysis.bayesian.models.{
  BetaModel,
  LogNormalModel,
  NormalModel
}

trait KPIIndicator[Model] {
  def apply(
      model: Model
    ): Indicator
}

object KPIIndicator {

  implicit val betaInstance: KPIIndicator[BetaModel] =
    (model: BetaModel) => Variable(model.prediction)

  implicit val normalInstance: KPIIndicator[NormalModel] =
    (model: NormalModel) => Variable(model.mean)

  implicit val logNormalInstance: KPIIndicator[LogNormalModel] =
    (model: LogNormalModel) => Variable(model.mean)
}
