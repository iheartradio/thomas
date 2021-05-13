package com.iheart.thomas
package analysis

import com.iheart.thomas.analysis.bayesian.Variable
import com.stripe.rainier.compute.Real
import io.estatico.newtype.macros.newsubtype

object `package` {
  @newsubtype case class Probability(p: Double)
  @newsubtype case class KPIDouble(d: Double)
  @newsubtype case class KPIName(n: String)
  @newsubtype case class QueryName(n: String)

  type Diff = Double
  type Samples[A] = List[A]

  object KPIName {
    def fromString(n: String): Either[String, KPIName] =
      if (n.matches("[-_.A-Za-z0-9]+"))
        Right(KPIName(n))
      else Left("KPI Name can only consists alphanumeric characters, '_' or '-'.")

  }

  type Indicator = Variable[Real]

  type ConversionEvent = Boolean
  val Converted = true
  val Initiated = false

}
