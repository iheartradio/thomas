package com.iheart.thomas
package bandit

import com.iheart.thomas.analysis.Conversions

package object bayesian {
  type Likelihood = Double

  type ConversionBMABAlg[F[_]] =
    BayesianMABAlgDepr[F, Conversions]

  type ConversionBandit =
    BayesianMABDepr[Conversions]
  type ConversionBandits = Vector[ConversionBandit]

}
