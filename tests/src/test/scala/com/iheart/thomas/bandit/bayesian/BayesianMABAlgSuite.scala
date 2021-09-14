package com.iheart.thomas
package bandit
package bayesian

import java.time.OffsetDateTime
import com.iheart.thomas.analysis.{KPIName, Probability}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AsyncFreeSpec
import cats.implicits._
import com.iheart.thomas.abtest.model.Group
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import scala.util.Try

class BayesianMABAlgSuite
    extends AnyFunSuiteLike
    with Matchers
    with ScalaCheckDrivenPropertyChecks {

  import com.iheart.thomas.abtest.BucketingTests.groupsGen

  implicit val distributionGen: Arbitrary[Map[GroupName, Probability]] = Arbitrary {
    groupsGen(3).map(_.map { group =>
      group.name -> Probability(group.size.doubleValue)
    }.toMap)
  }

  def createSpec(arms: Seq[ArmSpec]) =
    BanditSpec(
      "feature",
      "title",
      "author",
      KPIName("kpi"),
      arms,
      stateMonitorEventChunkSize = 1,
      updatePolicyStateChunkSize = 1
    )

  test("allocateGroupSize allocates using available size") {
    forAll { (distribution: Map[GroupName, Probability]) =>
      val precision = BigDecimal(0.01)
      val availableSize = BigDecimal(0.8)
      val groups = BayesianMABAlg
        .allocateGroupSize(distribution, precision, availableSize)

      groups.size shouldBe distribution.size
      val totalSize = groups.foldMap(_.size)

      totalSize should be(
        BigDecimal(
          distribution.values.toList.foldMap(_.p)
        ) * availableSize +- precision
      )

      totalSize should be <= availableSize

      groups
        .foreach { group =>
          group.size shouldBe BigDecimal(
            distribution(group.name).p
          ) * availableSize +- precision

          (group.size % precision) should be(BigDecimal(0))

        }
    }
  }

  test("abtestSpecFromBanditSpec distributes sizes evenly") {

    val result = BayesianMABAlg
      .createTestSpec[Try](
        createSpec(arms = List(ArmSpec("A"), ArmSpec("B"))),
        OffsetDateTime.now
      )

    result.isSuccess shouldBe true
    result.get.groups.map(_.size).sum shouldBe BigDecimal(1)
  }

  test("abtestSpecFromBanditSpec allocate based on initial sizes") {

    val result = BayesianMABAlg
      .createTestSpec[Try](
        createSpec(arms =
          List(ArmSpec("A", initialSize = Some(0.3)), ArmSpec("B"), ArmSpec("C"))
        ),
        OffsetDateTime.now
      )

    result.isSuccess shouldBe true

    def sizeOf(gn: GroupName) = result.get.groups.find(_.name == gn).get.size

    sizeOf("A") shouldBe BigDecimal(0.3)
    sizeOf("B") shouldBe sizeOf("C")
    result.get.groups.map(_.size).sum shouldBe BigDecimal(1)
  }

}
