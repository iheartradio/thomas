package com.iheart.thomas
package analysis

import com.stripe.rainier.compute.Real
import io.estatico.newtype.macros.newsubtype

object `package` {
  @newsubtype case class Probability(p: Double)
  @newsubtype case class KPIDouble(d: Double)
  @newsubtype case class KPIName(n: String)

  object KPIName {
    implicit def fromString(n: String): KPIName = KPIName(n)
  }

  type Measurements = List[Double]
  type Indicator = Variable[Real]

}
