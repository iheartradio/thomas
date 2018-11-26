package com.iheart.thomas.analysis

import com.stripe.rainier
import com.stripe.rainier.core.Distribution

sealed trait DistributionSpec[T] extends Serializable with Product{
  def distribution: Distribution[T]
}

object DistributionSpec {
  case class Normal(location: Double,
                    scale: Double) extends DistributionSpec[Double]  {
    val distribution = rainier.core.Normal(location, scale)
  }

  object Normal {
    def fit(data: List[Double]): Normal = {
      import breeze.stats.{meanAndVariance, MeanAndVariance}
      val MeanAndVariance(m, v, _) = meanAndVariance(data)
      Normal(m, Math.sqrt(v))
    }
  }

  case class LogNormal(location: Double,
                       scale: Double) extends DistributionSpec[Double] {
    val distribution = rainier.core.LogNormal(location, scale)

  }

  case class Gamma(shape: Double,
                   scale: Double) extends DistributionSpec[Double] {
    val distribution = rainier.core.Gamma(shape, scale)
  }

  case class Uniform(from: Double,
                     to: Double) extends DistributionSpec[Double] {
    val distribution = rainier.core.Uniform(from, to)
  }


}

