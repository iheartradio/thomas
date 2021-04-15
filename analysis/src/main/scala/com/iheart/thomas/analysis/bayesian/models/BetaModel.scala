package com.iheart.thomas.analysis
package bayesian.models

case class BetaModel(
    alpha: Double,
    beta: Double) {
  def updateFrom(conversions: Conversions): BetaModel =
    copy(
      alpha = conversions.converted + 1d,
      beta = conversions.total - conversions.converted + 1d
    )
}

object BetaModel {
  def apply(conversions: Conversions): BetaModel =
    BetaModel(
      alpha = conversions.converted + 1d,
      beta = conversions.total - conversions.converted + 1d
    )
}
