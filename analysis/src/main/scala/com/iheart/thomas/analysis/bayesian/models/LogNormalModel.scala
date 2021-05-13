package com.iheart.thomas.analysis.bayesian.models

import cats.data.ValidatedNel
import cats.implicits._
import com.stripe.rainier.compute.Real

case class LogNormalModel(inner: NormalModel) {
  lazy val mean: Real = (inner.mean + (inner.variance / 2d)).exp
}
object LogNormalModel {
  def validate(model: LogNormalModel): ValidatedNel[String, LogNormalModel] =
    NormalModel.validate(model.inner).as(model)
}
