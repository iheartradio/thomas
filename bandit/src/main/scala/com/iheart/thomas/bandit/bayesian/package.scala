package com.iheart.thomas
package bandit

import com.iheart.thomas.analysis.Conversions

package object bayesian {
  type Likelihood = Double

  type ConversionBMABAlg[F[_]] = BayesianMABAlg[F, Conversions]
}
