package com.iheart.thomas
package analysis
import io.estatico.newtype.ops._
import implicits._
import com.iheart.thomas.analysis.DistributionSpec.Normal
import com.iheart.thomas.analysis.Measurable.SamplerSettings
import com.stripe.rainier.core.Gamma
import com.stripe.rainier.sampler.RNG
import org.scalatest.{FunSuite, Matchers}
import com.stripe.rainier.repl.plot1D
import scala.util.Random
class MeasurableSuite extends FunSuite with Matchers {
  implicit val sampleSettings = SamplerSettings.default
  implicit val rng = RNG.default

  test("Measure one group against control generates result") {
    val n = 1000
    val groupData = Random.shuffle(Gamma(0.55, 3).param.sample()).take(n)
    val controlData = Random.shuffle(Gamma(0.5, 3).param.sample()).take(n)
    val result = GammaKPI(KPIName("test"),
      Normal(0.5, 0.1),
      Normal(3, 0.1)
    ).assess(Map("A" -> groupData), controlData)



    result.keys should contain("A")
    plot1D(result("A").indicatorSample.coerce[List[Double]])
    println(result("A").copy(indicatorSample = Nil))
    result("A").expectedEffect.d shouldBe (0.15 +- 0.2)
    result("A").probabilityOfImprovement.p shouldBe (0.9 +- 0.2)
    result("A").risk.d shouldBe (0.1 +- 0.2)

  }

  test("Measure A/B/C") {
    val n = 100
    val groupData = Random.shuffle(Gamma(0.55, 3).param.sample()).take(n)
    val group2Data = Random.shuffle(Gamma(0.55, 3).param.sample()).take(n)
    val controlData = Random.shuffle(Gamma(0.5, 3).param.sample()).take(n)
    val resultEither = GammaKPI(KPIName("test"),
      Normal(0.5, 0.1),
      Normal(3, 0.1)
    ).assess(Map("A" -> controlData, "B" -> groupData, "C" -> group2Data), "A")

    resultEither.isRight shouldBe true

    val result = resultEither.right.get

    result.keys should contain("B")
    result.keys should contain("C")
    result.keys shouldNot contain("A")

  }
}
