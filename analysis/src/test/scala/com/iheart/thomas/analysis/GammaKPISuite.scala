package com.iheart.thomas
package analysis

import java.io.File
import java.time.OffsetDateTime

import cats.{Applicative, Id}
import cats.effect.IO
import io.estatico.newtype.ops._
import implicits._
import com.iheart.thomas.analysis.DistributionSpec.Normal
import com.iheart.thomas.abtest.model.Abtest
import com.stripe.rainier.core.Gamma
import com.stripe.rainier.sampler._
import org.scalatest.Matchers
import org.scalatest.funsuite.AnyFunSuiteLike

import com.stripe.rainier.repl.plot1D
import cats.implicits._

import scala.util.Random

class GammaKPISuite extends AnyFunSuiteLike with Matchers {
  implicit val rng = RNG.default
  implicit val sampleSettings = SampleSettings.default
  type F[A] = Either[Throwable, A]
  def mock(
      abTestData: Map[GroupName, Measurements] = Map(),
      historical: Measurements = Nil): Measurable[F, Measurements, GammaKPIDistribution] =
    new Measurable[F, Measurements, GammaKPIDistribution] {
      def measureAbtest(
          k: GammaKPIDistribution,
          abtest: Abtest,
          start: Option[OffsetDateTime] = None,
          end: Option[OffsetDateTime] = None): F[Map[GroupName, Measurements]] =
        abTestData.asRight
      def measureHistory(k: GammaKPIDistribution,
                         start: OffsetDateTime,
                         end: OffsetDateTime): F[Measurements] = historical.asRight
    }

  val mockAb: Abtest = null

  test("Measure one group against control generates result") {

    val n = 1000
    implicit val measurable = mock(
      Map("A" -> Random.shuffle(Gamma(0.55, 3).param.sample()).take(n),
          "B" -> Random.shuffle(Gamma(0.5, 3).param.sample()).take(n)))
    AssessmentAlg[F, GammaKPIDistribution]
    val resultEither = GammaKPIDistribution(KPIName("test"),
                                            Normal(0.5, 0.1),
                                            Normal(3, 0.1)).assess(mockAb, "B")

    resultEither.isRight shouldBe true
    val result = resultEither.right.get

    result.keys should contain("A")
    plot1D(result("A").indicatorSample.coerce[List[Double]])

    result("A").expectedEffect.d shouldBe (0.15 +- 0.2)
    result("A").probabilityOfImprovement.p shouldBe (0.9 +- 0.4)
    result("A").riskOfUsing.d shouldBe (0.1 +- 0.3)

  }

  test("Measure A/B/C") {

    val n = 100
    implicit val measurable = mock(
      Map(
        "A" -> Random.shuffle(Gamma(0.5, 3).param.sample()).take(n),
        "B" -> Random.shuffle(Gamma(0.55, 3).param.sample()).take(n),
        "C" -> Random.shuffle(Gamma(0.55, 3).param.sample()).take(n)
      ))

    val resultEither = GammaKPIDistribution(KPIName("test"),
                                            Normal(0.5, 0.1),
                                            Normal(3, 0.1)).assess(mockAb, "A")

    resultEither.isRight shouldBe true

    val result = resultEither.right.get

    result.keys should contain("B")
    result.keys should contain("C")
    result.keys shouldNot contain("A")

  }

  test("Diagnostic trace") {

    val n = 1000
    implicit val measurable = mock(
      Map(
        "A" -> Random.shuffle(Gamma(0.5, 3).param.sample()).take(n),
        "B" -> Random.shuffle(Gamma(0.55, 3).param.sample()).take(n)
      ))
    val result = GammaKPIDistribution(KPIName("test"), Normal(0.5, 0.1), Normal(3, 0.1))
      .assess(mockAb, "A")
      .right
      .get

    val path = "plots/diagnosticTraceTest.png"
    new File(path).delete()

    new File("plots").mkdir()
    result("B").trace[IO](path).unsafeRunSync()

    new File(path).exists() shouldBe true

    new File(path).delete()
  }

  test("updated with new piror") {

    implicit val measurable =
      mock(historical = Random.shuffle(Gamma(0.55, 3).param.sample(5000)))

    val resultEither =
      GammaKPIDistribution(KPIName("test"), Normal(0.8, 0.2), Normal(6, 1))
        .updateFromData[F](OffsetDateTime.now.minusDays(1), OffsetDateTime.now)

    resultEither.isRight shouldBe true

    val result = resultEither.right.get
    result._1.shapePrior.location shouldBe (0.55 +- 0.1)
    result._1.scalePrior.location shouldBe (3d +- 1d)
    result._2 shouldBe <(0.5)

  }
}
