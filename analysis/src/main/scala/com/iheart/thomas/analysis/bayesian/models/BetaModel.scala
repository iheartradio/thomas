package com.iheart.thomas.analysis
package bayesian.models

case class BetaModel(
    alphaPrior: Double,
    betaPrior: Double) {
  def updateFrom(conversions: Conversions): BetaModel =
    copy(
      alphaPrior = conversions.converted + 1d,
      betaPrior = conversions.total - conversions.converted + 1d
    )

  def accumulativeUpdate(
      c: Conversions
    ): BetaModel = {
    copy(
      alphaPrior = alphaPrior + c.converted.toDouble,
      betaPrior = betaPrior + c.total.toDouble - c.converted.toDouble
    )
  }
}
