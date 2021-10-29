package com.iheart.thomas
package analysis
package bayesian

import cats.Id
import com.iheart.thomas.analysis.bayesian.models.LogNormalModel
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

class ModelEvaluatorSuite extends AnyFreeSpecLike with Matchers {

  "LogNormal Evaluator" - {
    val evaluator =
      implicitly[ModelEvaluator[Id, LogNormalModel, PerUserSamples.LnSummary]]
    "compare two data samples" in {
      val n = 10000
      val dist1 = breeze.stats.distributions.LogNormal(mu = 0d, sigma = 0.3d)
      val dist2 = breeze.stats.distributions.LogNormal(mu = 0.01d, sigma = 0.3d)
      val data1 = PerUserSamples(dist1.sample(n).toArray).lnSummary

      val data2 = PerUserSamples(dist2.sample(n).toArray).lnSummary
      val model = LogNormalModel(0, 1, 1, 1)

      val result = evaluator.evaluate(
        Map(
          ("data1", (data1, model)),
          ("data2", (data2, model))
        )
      )

      result("data2").p shouldBe >(result("data1").p)
    }

    "compare more realistic samples" in {
      val dataC =
        PerUserSamplesLnSummary(mean = -0.3106, variance = 3.8053, count = 1989360)
      val dataB =
        PerUserSamplesLnSummary(mean = -0.3115, variance = 3.8159, count = 1990479)
      val dataA =
        PerUserSamplesLnSummary(mean = -0.3089, variance = 3.8009, count = 2051370)

      val model =
        LogNormalModel(miu0 = -0.3050, n0 = 49095, alpha = 24547, beta = 94820)

      val result = evaluator.evaluate(
        Map(
          ("C", (dataC, model)),
          ("B", (dataB, model)),
          ("A", (dataA, model))
        )
      )

      result("A").p shouldBe <(result("B").p) // due to the higher variance.
    }
  }

}
