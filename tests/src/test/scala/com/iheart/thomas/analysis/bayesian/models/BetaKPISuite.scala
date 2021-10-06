package com.iheart.thomas.analysis
package bayesian
package models

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.implicits._
import com.iheart.thomas.abtest.model.Abtest
import com.stripe.rainier.sampler.{RNG, SamplerConfig}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

class BetaKPISuite extends AsyncFreeSpec with AsyncIOSpec with Matchers {
  implicit val rng = RNG.default
  implicit val sampler = SamplerConfig.default

  val mockAb: Abtest = null
  val alg: ModelEvaluator[IO, BetaModel, Conversions] =
    implicitly

  "BetaKPI Assessment Alg" - {
    "can evaluation optimal group distribution" in {
      alg
        .evaluate(
          BetaModel(200d, 300d),
          Map(
            "A" -> Conversions(200L, 300L),
            "B" -> Conversions(250L, 300L),
            "C" -> Conversions(265L, 300L),
            "D" -> Conversions(230L, 300L)
          ),
          None
        )
        .asserting { dist =>
          dist
            .sortBy(_.probabilityBeingOptimal.p)
            .map(_.name) shouldBe List("A", "D", "B", "C")
        }

    }
  }
}
