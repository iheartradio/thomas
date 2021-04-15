package com.iheart.thomas.analysis.bayesian.models

/**
  * xi | µ, τ ∼ N (µ, τ ) i.i.d.
  * µ | τ ∼ N (µ0, n0τ )
  * τ ∼ Ga(α, β)
  * @param miu0 µ
  * @param n0 n0
  * @param alpha α
  * @param beta β
  */
case class NormalModel(
    miu0: Double,
    n0: Double,
    alpha: Double,
    beta: Double)
