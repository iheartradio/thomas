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

  def accumulativeUpdate(
      c: Conversions
    ): BetaModel = {
    copy(
      alpha = alpha + c.converted.toDouble,
      beta = beta + c.total.toDouble - c.converted.toDouble
    )
  }
}
