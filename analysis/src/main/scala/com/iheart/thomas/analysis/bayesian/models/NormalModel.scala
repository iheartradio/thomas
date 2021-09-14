package com.iheart.thomas.analysis
package bayesian.models
import syntax.all._
import cats.implicits._
import cats.data.ValidatedNel
import com.stripe.rainier.core.{Gamma, Normal}

/** xi | µ, τ ∼ N (µ, τ ) i.i.d. µ | τ ∼ N (µ0, n0τ ) τ ∼ Ga(α, β)
  *
  * @param miu0
  *   µ
  * @param n0
  *   n0
  * @param alpha
  *   α
  * @param beta
  *   β
  */
case class NormalModel(
    miu0: Double,
    n0: Double,
    alpha: Double,
    beta: Double) {

  lazy val τ = Gamma(shape = alpha, scale = 1d / beta).latent
  lazy val variance = 1d / τ
  lazy val mean = Normal(miu0, (1d / (τ * n0)).pow(0.5)).latent
}

object NormalModel {
  def validate(model: NormalModel): ValidatedNel[String, NormalModel] =
    (model.beta > 0).toValidatedNel(model, "Beta must be larger than zero") <*
      (model.n0 > 0).toValidatedNel(model, "n0 must be larger than zero") <*
      (model.alpha > 0).toValidatedNel(model, "Alpha must be larger than zero")
}
