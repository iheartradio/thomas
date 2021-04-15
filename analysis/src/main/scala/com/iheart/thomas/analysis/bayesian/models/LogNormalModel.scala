package com.iheart.thomas.analysis.bayesian.models

import cats.data.ValidatedNel
import cats.implicits._

case class LogNormalModel(inner: NormalModel) {
  lazy val mean = (inner.mean + (inner.variance / 2)).exp
}
object LogNormalModel {
  def validate(model: LogNormalModel): ValidatedNel[String, LogNormalModel] =
    NormalModel.validate(model.inner).as(model)
}
