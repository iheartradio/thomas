package com.iheart.thomas.analysis

import java.time.Instant

import com.iheart.thomas.GroupName
import com.iheart.thomas.abtest.model.Abtest
import com.stripe.rainier.sampler.{RNG, SamplerConfig}
import org.scalatest.matchers.should.Matchers
import cats.implicits._
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.IO

class BetaKPISuite extends AsyncIOSpec with Matchers {
  implicit val rng = RNG.default
  implicit val sampler = SamplerConfig.default

  val mockAb: Abtest = null
  val alg: KPIEvaluator[IO, BetaModel, Conversions] =
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
            .mapValues(_.probabilityBeingOptimal)
            .toList
            .sortBy(_._2.p)
            .map(_._1) shouldBe List("A", "D", "B", "C")
        }

    }
  }
}
