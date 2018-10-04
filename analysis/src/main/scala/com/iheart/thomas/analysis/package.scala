package com.iheart.thomas
package analysis

import com.stripe.rainier.compute.Real
import com.stripe.rainier.core.RandomVariable
import io.estatico.newtype.macros.{newsubtype}

object `package` {
  @newsubtype case class Probability(p: Double)
  @newsubtype case class KPIDouble(d: Double)
  @newsubtype case class KPIName(n: String)

  type Indicator = RandomVariable[Real]
  type Measurements = List[Double]

}
