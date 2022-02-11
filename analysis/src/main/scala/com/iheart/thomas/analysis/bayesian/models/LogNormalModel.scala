package com.iheart.thomas.analysis.bayesian.models

import cats.data.ValidatedNel
import cats.syntax.all._
import com.stripe.rainier.compute.Real

case class LogNormalModel(inner: NormalModel) {
  lazy val mean: Real = (inner.mean + (inner.variance / 2d)).exp
}
object LogNormalModel {
  def apply(
      miu0: Double,
      n0: Double,
      alpha: Double,
      beta: Double
    ): LogNormalModel = LogNormalModel(NormalModel(miu0, n0, alpha, beta))

  def validate(model: LogNormalModel): ValidatedNel[String, LogNormalModel] =
    NormalModel.validate(model.inner).as(model)
}
