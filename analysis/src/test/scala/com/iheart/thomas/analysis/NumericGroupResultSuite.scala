package com.iheart.thomas.analysis

import syntax.AllSyntax
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AnyFunSuiteLike

class NumericGroupResultSuite extends AnyFunSuiteLike with Matchers with AllSyntax {
  test("probability of Improvement") {
    val subject = NumericGroupResult(List(-1, 1, -3, 2, -3))
    subject.probabilityOfImprovement shouldBe Probability(0.4d)
  }

  test("riskOfUsing") {
    val subject = NumericGroupResult((-6 to 93).toList.map(_.toDouble))
    subject.riskOfUsing shouldBe -2d
  }

  test("riskOf Not Using") {
    val subject = NumericGroupResult((-50 to 50).toList.map(_.toDouble))
    subject.riskOfNotUsing shouldBe -44d
  }

  test("expected effect") {
    val subject = NumericGroupResult((-50 to 50).toList.map(_.toDouble))
    subject.expectedEffect shouldBe 0d
  }
}
