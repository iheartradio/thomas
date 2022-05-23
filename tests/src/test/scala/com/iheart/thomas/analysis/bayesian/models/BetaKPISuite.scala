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
            "B" -> Conversions(250L, 300L),
            "A" -> Conversions(200L, 300L),
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

    "can evaluate optimal group distribution with large sample size" in {
      alg
        .evaluate(
          BetaModel(0d, 0d),
          Map(
            "A" -> Conversions(7000L, 10000L),
            "B" -> Conversions(6800, 10000L),
            "C" -> Conversions(6700L, 10000L),
            "D" -> Conversions(7500L, 10000L)
          ),
          None
        )
        .asserting { dist =>
          dist
            .sortBy(_.probabilityBeingOptimal.p)
            .map(_.name).last shouldBe  "D"
        }
    }

    "can evaluate optimal group distribution with asymmetric sample size" in {
      alg
        .evaluate(
          BetaModel(0d, 0d),
          Map(
            "A" -> Conversions(70L, 100L),
            "B" -> Conversions(680, 1000L),
            "C" -> Conversions(6700L, 10000L),
            "D" -> Conversions(750L, 1000L)
          ),
          None
        )
        .asserting { dist =>
          dist
            .sortBy(_.probabilityBeingOptimal.p)
            .map(_.name).last shouldBe  "D"
        }
    }

    "can evaluate optimal group distribution with real data" in {
      alg
        .evaluate(
          BetaModel(1414L, 500L),
          Map(
            "A" -> Conversions(1414L, 1973L),
            "C" -> Conversions(20985L, 31267L)
          ),
          None
        )
        .asserting { dist =>
          dist
            .sortBy(_.probabilityBeingOptimal.p)
            .map(_.name).last shouldBe  "A"
        }
    }

  }
}
