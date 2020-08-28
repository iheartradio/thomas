package com.iheart.thomas
package bandit.bayesian

import java.time.OffsetDateTime

import com.iheart.thomas.GroupName
import com.iheart.thomas.analysis.Probability
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import cats.implicits._
import com.iheart.thomas.abtest.model.Group
import com.iheart.thomas.bandit.{ArmSpec, BanditSpec}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import scala.util.Try

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

  def createSettings =
    BanditSettings[BanditSettings.Conversion](
      "feature",
      "title",
      "author",
      "kpi",
      distSpecificSettings = BanditSettings.Conversion(1, 1)
    )

  test("allocateGroupSize allocates using available size") {
    forAll { (distribution: Map[GroupName, Probability]) =>
      val precision = BigDecimal(0.01)
      val availableSize = BigDecimal(0.8)
      val groups = BayesianMABAlg
        .allocateGroupSize(distribution, precision, None, availableSize)

      groups.size shouldBe distribution.size
      val totalSize = groups.foldMap(_.size)

      totalSize should be(
        BigDecimal(distribution.values.toList.foldMap(_.p)) * availableSize +- precision
      )

      totalSize should be <= availableSize

      groups
        .foreach { group =>
          group.size shouldBe BigDecimal(distribution(group.name).p) * availableSize +- precision

          (group.size % precision) should be(BigDecimal(0))

        }
    }
  }

  test("allocateGroupSize respect maintain exploration size") {
    val distribution: Map[GroupName, Probability] =
      Map("A" -> Probability(0.001), "B" -> Probability(0.999))
    val precision = BigDecimal(0.01)
    val groups = BayesianMABAlg
      .allocateGroupSize(distribution, precision, Some(0.1d), 1)

    groups.toSet shouldBe Set(
      Group("A", BigDecimal(0.1), None),
      Group("B", BigDecimal(0.9), None)
    )

  }

  test("abtestSpecFromBanditSpec distributes sizes evenly") {

    val result = BayesianMABAlg
      .createTestSpec[Try](
        BanditSpec(
          arms = List(ArmSpec("A"), ArmSpec("B")),
          OffsetDateTime.now,
          createSettings
        )
      )

    result.isSuccess shouldBe true
    result.get.groups.map(_.size).sum shouldBe BigDecimal(1)
  }

  test("abtestSpecFromBanditSpec allocate based on initial sizes") {

    val result = BayesianMABAlg
      .createTestSpec[Try](
        BanditSpec(
          arms =
            List(ArmSpec("A", initialSize = Some(0.3)), ArmSpec("B"), ArmSpec("C")),
          OffsetDateTime.now,
          createSettings
        )
      )

    result.isSuccess shouldBe true

    def sizeOf(gn: GroupName) = result.get.groups.find(_.name == gn).get.size

    sizeOf("A") shouldBe BigDecimal(0.3)
    sizeOf("B") shouldBe sizeOf("C")
    result.get.groups.map(_.size).sum shouldBe BigDecimal(1)
  }

}
