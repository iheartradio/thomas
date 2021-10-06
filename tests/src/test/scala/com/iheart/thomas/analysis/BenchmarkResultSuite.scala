package com.iheart.thomas.analysis

import com.iheart.thomas.analysis.bayesian.BenchmarkResult
import com.iheart.thomas.analysis.syntax.AllSyntax
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers

class BenchmarkResultSuite extends AnyFunSuiteLike with Matchers with AllSyntax {
  test("probability of Improvement") {
    val subject = bayesian.BenchmarkResult(List(-1, 1, -3, 2, -3), "B")
    subject.probabilityOfImprovement shouldBe Probability(0.4d)
  }

  test("riskOfUsing") {
    val subject = BenchmarkResult((-6 to 93).toList.map(_.toDouble), "B")
    subject.riskOfUsing shouldBe -2d
  }

  test("riskOf Not Using") {
    val subject = bayesian.BenchmarkResult((-50 to 50).toList.map(_.toDouble), "B")
    subject.riskOfNotUsing shouldBe -44d
  }

  test("expected effect") {
    val subject = bayesian.BenchmarkResult((-50 to 50).toList.map(_.toDouble), "B")
    subject.expectedEffect shouldBe 0d
  }
}
