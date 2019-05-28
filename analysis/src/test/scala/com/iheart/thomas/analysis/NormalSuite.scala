package com.iheart.thomas
package analysis

import DistributionSpec.Normal
import syntax.AllSyntax
import com.stripe.rainier.sampler.RNG
import org.scalatest.Matchers
import org.scalatest.funsuite.AnyFunSuiteLike


class NormalSuite extends AnyFunSuiteLike with Matchers with AllSyntax {
  implicit val rng = RNG.default

  test("Normal fit consistent with spec") {
    val data = Normal(13, 4).distribution.param.sample(SampleSettings.default)
    val fitSpec = Normal.fit(data)
    fitSpec.location shouldBe (13d +- 0.65)
    fitSpec.scale shouldBe (4d +- 0.2)
  }
}
