package com.iheart.thomas
package bandit.bayesian

import com.iheart.thomas.GroupName
import com.iheart.thomas.analysis.Probability
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import cats.implicits._
import com.iheart.thomas.abtest.model.Group
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class ConversionBMABAlgSuite
    extends AnyFunSuiteLike
    with Matchers
    with ScalaCheckDrivenPropertyChecks {

  import com.iheart.thomas.abtest.BucketingTests.groupsGen

  implicit val distributionGen: Arbitrary[Map[GroupName, Probability]] = Arbitrary {
    groupsGen(3).map(_.map { group =>
      group.name -> Probability(group.size.doubleValue)
    }.toMap)
  }

  test("allocateGroupSize allocates to specific precision") {
    forAll { (distribution: Map[GroupName, Probability]) =>
      val precision = BigDecimal(0.01)
      val groups = ConversionBMABAlg
        .allocateGroupSize(distribution, precision, None)

      groups.size shouldBe distribution.size

      val totalSize = groups.foldMap(_.size)

      totalSize should be(
        BigDecimal(distribution.values.toList.foldMap(_.p)) +- precision
      )
      totalSize should be <= BigDecimal(1)

      groups
        .foreach { group =>
          group.size shouldBe BigDecimal(distribution(group.name).p) +- precision

          (group.size % precision) should be(BigDecimal(0))

        }
    }
  }

  test("allocateGroupSize respect maintain exploration size") {
    val distribution: Map[GroupName, Probability] =
      Map("A" -> Probability(0.001), "B" -> Probability(0.999))
    val precision = BigDecimal(0.01)
    val groups = ConversionBMABAlg
      .allocateGroupSize(distribution, precision, Some(0.1d))

    groups.toSet shouldBe Set(
      Group("A", BigDecimal(0.1)),
      Group("B", BigDecimal(0.9))
    )

  }

}
