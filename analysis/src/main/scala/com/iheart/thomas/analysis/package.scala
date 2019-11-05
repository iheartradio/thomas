package com.iheart.thomas
package analysis

import com.stripe.rainier.compute.Real
import com.stripe.rainier.core.RandomVariable
import io.estatico.newtype.macros.{newsubtype}

object `package` {
  @newsubtype case class Probability(p: Double)
  @newsubtype case class KPIDouble(d: Double)
  @newsubtype case class KPIName(n: String)

  object KPIName {
    implicit def fromString(n: String): KPIName = KPIName(n)
  }

  type Indicator = RandomVariable[Real]
  type Measurements = List[Double]
}

case class Conversions(
    converted: Long,
    total: Long) {
  def rate = converted.toDouble / total.toDouble
}
