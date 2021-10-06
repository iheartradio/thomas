package com.iheart.thomas
package abtest

import com.iheart.thomas.utils.time._
import org.scalacheck.Arbitrary
import org.scalacheck.Gen._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import java.time.Instant
import scala.concurrent.duration._

class TestsDataSuite
    extends AnyFreeSpec
    with ScalaCheckDrivenPropertyChecks
    with Matchers {

  "TestsData.withinTolerance" - {

    "indicate if the target date is within the tolerance band" in {
      forAll {
        (
            testsData: TestsData,
            toleranceR: FiniteDuration,
            offset: Long
        ) =>
          val tolerance = toleranceR.toNanos.abs.nanos
          val target = testsData.at.plusNanos(offset)
          val cutOffTimeBegin = testsData.at.minusNanos(tolerance.toNanos.abs)

          val endTime =
            testsData.duration.fold(testsData.at)(testsData.at.plusDuration)
          val cutOffTimeEnd = endTime.plusNanos(tolerance.toNanos.abs)

          val withinRange = !target.isBefore(cutOffTimeBegin) && !target.isAfter(
            cutOffTimeEnd
          )

          testsData.withinTolerance(tolerance, target) shouldBe withinRange
      }
    }

    "return true if the band is zero and the time is the same" in {
      val t = Instant.now
      TestsData(at = t, Vector.empty, None)
        .withinTolerance(Duration.Zero, t) shouldBe true
    }
  }

  implicit val arbTestsData: Arbitrary[TestsData] = Arbitrary {
    for {
      at <- choose(-1000000000L, 10000000000L)
      duration <- option(choose(Duration.Zero, 1000000000000L.nanos))
    } yield TestsData(Instant.now.plusMillis(at), Vector.empty, duration)
  }

}
