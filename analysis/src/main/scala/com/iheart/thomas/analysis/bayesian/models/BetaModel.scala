package com.iheart.thomas.analysis
package bayesian.models
import syntax.all._
import cats.data.ValidatedNel
import com.stripe.rainier.core.Beta
import cats.syntax.all._
case class BetaModel(
    alpha: Double,
    beta: Double) {
  lazy val prediction = Beta(alpha, beta).latent

  override def toString: String = {
    val converted = alpha - 1d
    val total = beta - 1 + converted
    s"Beta Model based on converted: $converted total: $total"
  }
}

object BetaModel {
  def apply(conversions: Conversions): BetaModel =
    BetaModel(
      alpha = conversions.converted + 1d,
      beta = conversions.total - conversions.converted + 1d
    )

  def validate(model: BetaModel): ValidatedNel[String, BetaModel] =
    (model.beta > 0).toValidatedNel(model, "Beta must be larger than zero") <*
      (model.alpha > 0).toValidatedNel(model, "Alpha must be larger than zero")

}
