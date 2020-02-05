package com.iheart.thomas

import java.time.OffsetDateTime

import cats.effect.testing.scalatest.AsyncIOSpec
import com.iheart.thomas.abtest.model.UserGroupQuery
import com.iheart.thomas.testkit.Factory._
import org.scalatest.matchers.should.Matchers

class AbtestAlgSuite extends AsyncIOSpec with Matchers {
  val algR = testkit.Resources.apis.map(_._3)
  val now = OffsetDateTime.now
  val anHourLater = Some(now.plusHours(1))

  "AbtestAlg" - {

    "does not control eligibility for tests without eligibility control type" in {

      algR.use { alg =>
        for {
          t <- alg.create(fakeAb(), false)
          r <- alg.getGroupsWithMeta(
            UserGroupQuery(Some("random"), at = anHourLater)
          )

        } yield {
          r.groups.contains(t.data.feature) shouldBe true
        }
      }
    }

    "does not include tests with eligibility control eligibilityInfo is not included in the query" in {

      algR.use { alg =>
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
        } yield {
          r.groups.contains(t.data.feature) shouldBe false
        }
      }
    }

  }
}
