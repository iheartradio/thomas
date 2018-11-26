package com.iheart.thomas
package analysis
package syntax

import com.stripe.rainier.core.{RandomVariable, Sampleable}
import com.stripe.rainier.sampler.RNG

trait RandomVariableSyntax {
  implicit class RandomVariableOps[T](private val rv: RandomVariable[T]) {
    def sample[V](sampleSettings: SampleSettings)(
      implicit rng: RNG, sampleable: Sampleable[T, V]): List[V] = {
      import sampleSettings._
      rv.sample(sampler,
        warmupIterations = warmupIterations,
        iterations = iterations,
        keepEvery = keepEvery)
    }

  }
}
