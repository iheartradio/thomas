package com.iheart.thomas.bandit.bayesian

import com.iheart.thomas.GroupName
import com.iheart.thomas.analysis.Probability
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import cats.implicits._
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class ConversionBMABAlgSuite
    extends AnyFunSuiteLike
    with Matchers
    with ScalaCheckDrivenPropertyChecks {
  import com.iheart.thomas.abtest.BucketingTests.groupsGen

  implicit val distributionGen: Arbitrary[Map[GroupName, Probability]] = Arbitrary {
    groupsGen(3).map(_.map { group =>
      group.name -> Probability(group.size)
    }.toMap)
  }

  test("allocateGroupSize allocates to specific precision") {
    forAll { (distribution: Map[GroupName, Probability]) =>
      val precision = 0.01d
      val groups = ConversionBMABAlg
        .allocateGroupSize(distribution, precision)

      groups.size shouldBe distribution.size

      val totalSize = groups.foldMap(_.size)

      totalSize should be(
        distribution.values.toList.foldMap(_.p) +- precision
      )
      totalSize should be <= 1d

      groups
        .foreach { group =>
          group.size shouldBe distribution(group.name).p +- precision

          group.size % precision should (be(0d +- 0.00001) or be(
            precision +- 0.00001
          ))
        }

    }
  }

}
