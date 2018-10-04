package com.iheart.thomas.analysis

import com.stripe.rainier
import com.stripe.rainier.core.Distribution
import rainier.compute.Real

sealed trait DistributionSpec[T] {
  def distribution: Distribution[T]
}

object DistributionSpec {
  case class Normal(location: Real,
                    scale: Real) extends DistributionSpec[Double]  {
    val distribution = rainier.core.Normal(location, scale)
  }

  case class LogNormal(location: Real,
                       scale: Real) extends DistributionSpec[Double] {
    val distribution = rainier.core.LogNormal(location, scale)

  }

  case class Gamma(shape: Real,
                   scale: Real) extends DistributionSpec[Double] {
    val distribution = rainier.core.Gamma(shape, scale)
  }

  case class Uniform(from: Real,
                     to: Real) extends DistributionSpec[Double] {
    val distribution = rainier.core.Uniform(from, to)
  }


}

