package com.iheart.thomas.analysis.bayesian.fit

import cats.implicits._
import com.iheart.thomas.GroupName
import com.iheart.thomas.abtest.model.Abtest
import com.iheart.thomas.analysis.DistributionSpec.{Normal, Uniform}
import com.iheart.thomas.analysis.`package`.Measurements
import com.iheart.thomas.analysis.implicits._
import com.iheart.thomas.analysis.KPIName
import com.stripe.rainier.core.LogNormal
import com.stripe.rainier.sampler._
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.util.Random

class LogNormalFitSuite extends AnyFunSuiteLike with Matchers {
  implicit val rng = RNG.default
  implicit val sampler = SamplerConfig.default //.copy(iterations = 10000)

  type F[A] = Either[Throwable, A]
  def mock(
      abTestData: Map[GroupName, Measurements] = Map(),
      historical: Measurements = Nil
    ): Measurable[F, Measurements, LogNormalFit] =
    new Measurable[F, Measurements, LogNormalFit] {
      def measureAbtest(
          kmodel: LogNormalFit,
          abtest: Abtest,
          start: Option[Instant] = None,
          end: Option[Instant] = None
        ): F[Map[GroupName, Measurements]] =
        abTestData.asRight
      def measureHistory(
          k: LogNormalFit,
          start: Instant,
          end: Instant
        ): F[Measurements] = historical.asRight
    }

  val mockAb: Abtest = null

  def meanLogNormal(
      location: Double,
      scale: Double
    ): Double =
    Math.exp(location + (Math.pow(scale, 2d) / 2d))

  test("Measure one group against control generates result") {

    val n = 10000
    val location = 0d
    val locationA = location + 0.1d
    val scale = 0.5d
    val data =
      Map(
        "A" -> Random
          .shuffle(LogNormal(locationA, scale).latent.sample)
          .take(n),
        "B" -> Random.shuffle(LogNormal(location, scale).latent.sample).take(n)
      )

    implicit val measurable = mock(data)

    val resultEither = LogNormalFit(
      Normal(location, 0.3),
      Uniform(0, scale * 3)
    ).assess(mockAb, "B")

    resultEither.isRight shouldBe true
    val result = resultEither.right.get

    result.keys should contain("A")

    val expectedEffect = meanLogNormal(locationA, scale) - meanLogNormal(
      location,
      scale
    )

    (result("A").expectedEffect.d > 0) shouldBe (expectedEffect > 0)
    result("A").expectedEffect.d / expectedEffect shouldBe (1d +- 2d)
    result("A").probabilityOfImprovement.p shouldBe (0.9 +- 0.2)
    result("A").riskOfUsing.d shouldBe (0.01 +- 0.15)

  }

  test("Measure A/B/C") {

    val n = 100
    implicit val measurable = mock(
      Map(
        "A" -> Random.shuffle(LogNormal(0.5, 3).latent.sample).take(n),
        "B" -> Random.shuffle(LogNormal(0.55, 3).latent.sample).take(n),
        "C" -> Random.shuffle(LogNormal(0.55, 3).latent.sample).take(n)
      )
    )

    val resultEither = LogNormalFit(
      Normal(0.5, 0.1),
      Uniform(0, 5)
    ).assess(mockAb, "A")

    resultEither.isRight shouldBe true

    val result = resultEither.right.get

    result.keys should contain("B")
    result.keys should contain("C")
    result.keys shouldNot contain("A")

  }

  test("updated with new prior") {

    implicit val measurable =
      mock(
        historical = Random.shuffle(LogNormal(0.01, 0.3).latent.sample)
      )

    val resultEither =
      LogNormalFit(Normal(2, 12), Uniform(3, 5))
        .updateFromData[F](
          Instant.now.minus(1, ChronoUnit.DAYS),
          Instant.now
        )

    resultEither.isRight shouldBe true

    val result = resultEither.right.get
    result._1.locationPrior.location shouldBe (0.01 +- 0.1)
    result._1.scaleLnPrior.from shouldBe (0d +- 1d)
    result._2 shouldBe <(0.5)

  }
}
