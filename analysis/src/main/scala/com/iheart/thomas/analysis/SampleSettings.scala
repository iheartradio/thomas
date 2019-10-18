package com.iheart.thomas
package analysis

import com.stripe.rainier.sampler.{Sampler, Walkers}

case class SampleSettings(
    sampler: Sampler,
    warmupIterations: Int,
    iterations: Int,
    keepEvery: Int = 1)

object SampleSettings {
  val default = SampleSettings(Walkers(100), 15000, 20000)
}
