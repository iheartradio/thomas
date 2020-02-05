package com.iheart.thomas

import java.time.OffsetDateTime

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.iheart.thomas.abtest.{AssignGroups, TestsData}
import com.iheart.thomas.abtest.AssignGroups.MissingEligibilityInfo
import com.iheart.thomas.abtest.model.UserGroupQuery
import com.iheart.thomas.testkit.Factory._
import org.scalatest.matchers.should.Matchers
import concurrent.duration._
class AbtestAlgSuite extends AsyncIOSpec with Matchers {
  val algR = testkit.Resources.apis.map(_._3)
  val now = OffsetDateTime.now
  val anHourLater = Some(now.plusHours(1))
  implicit val nowF = IO(now.toInstant)

  "AbtestAlg" - {

    "does not control eligibility for tests without eligibility control type" in {

      algR
        .use { alg =>
          for {
            t <- alg.create(fakeAb(), false)
            r <- alg.getGroupsWithMeta(
              UserGroupQuery(Some("random"), at = anHourLater)
            )

          } yield (r, t)
        }
        .asserting {
          case (r, t) =>
            r.groups.contains(t.data.feature) shouldBe true
        }
    }

    "does not include tests with eligibility control eligibilityInfo is not included in the query" in {

      algR
        .use { alg =>
          for {
            t <- alg.create(fakeAb(matchingUserMeta = Map("did" -> "aaa")), false)
            r <- alg.getGroupsWithMeta(
              UserGroupQuery(
                Some("random"),
                at = anHourLater,
                meta = Map("did" -> "aaa"),
                eligibilityInfoIncluded = false
              )
            )
          } yield (r, t)
        }
        .asserting {
          case (r, t) =>
            r.groups.contains(t.data.feature) shouldBe false

        }
    }

  }

  "AssignGroup" - {
    "assign with Missing Info for tests with eligibility control but Query missing info" in {
      algR
        .use { alg =>
          for {
            t <- alg.create(fakeAb(matchingUserMeta = Map("did" -> "aaa")), false)
            f <- alg.getOverrides(t.data.feature)
            r <- AssignGroups.assign[IO](
              TestsData(now.toInstant, Vector((t, f)), None),
              UserGroupQuery(
                Some("random"),
                at = anHourLater,
                eligibilityInfoIncluded = false
              ),
              1.day
            )
          } yield r
        }
        .asserting { r =>
          r.size shouldBe 1
          r.head._2 shouldBe MissingEligibilityInfo
        }

    }
  }
}
