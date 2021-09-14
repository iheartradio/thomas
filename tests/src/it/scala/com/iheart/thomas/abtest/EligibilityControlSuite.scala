package com.iheart.thomas.abtest

import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AsyncFreeSpec
import cats.implicits._
import TestUtils._
import com.iheart.thomas.abtest.model.UserMetaCriterion._

class EligibilityControlSuite extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  "AbtestAlg" - {
    "Matching Meta Integration" - {
      "not eligible to test if no matching meta" in {
        withAlg { alg =>
          for {
            ab <- alg.create(
              fakeAb(userMetaCriteria = Some(and(ExactMatch("sex", "M"))))
            )
            r1 <- alg.getGroupsWithMeta(q(randomUserId, Some(ab.data.start), Map()))
            r2 <- alg.getGroupsWithMeta(
              q(randomUserId, Some(ab.data.start), Map("sex" -> "F"))
            )
          } yield {
            r1.groups shouldBe empty
            r2.groups shouldBe empty
          }
        }

      }

      "eligible to test if there is matching meta" in {

        withAlg { alg =>
          for {
            ab <- alg.create(
              fakeAb(userMetaCriteria = Some(and(ExactMatch("sex", "M"))))
            )
            r <- alg.getGroupsWithMeta(
              q(randomUserId, Some(ab.data.start), Map("sex" -> "M"))
            )
          } yield {
            r.groups.keys should contain(ab.data.feature)

          }
        }
      }

      "eligible to test if there is matching meta with regex" in {
        withAlg { alg =>
          for {
            ab <- alg.create(
              fakeAb(userMetaCriteria = Some(and(RegexMatch("sex", "Male|^M$"))))
            )
            r1 <- alg.getGroupsWithMeta(
              q(randomUserId, Some(ab.data.start), Map("sex" -> "Male"))
            )
            r2 <- alg.getGroupsWithMeta(
              q(randomUserId, Some(ab.data.start), Map("sex" -> "M"))
            )
          } yield {
            r1.groups.keys should contain(ab.data.feature)
            r2.groups.keys should contain(ab.data.feature)

          }
        }
      }

      "Not eligible to test if there is one mismatch meta" in {

        withAlg { alg =>
          for {
            ab <- alg.create(
              fakeAb(
                userMetaCriteria = Some(
                  and(RegexMatch("sex", "Male|^M$"), RegexMatch("age", "^2\\d$"))
                )
              )
            )
            r <- alg.getGroupsWithMeta(
              q(
                randomUserId,
                Some(ab.data.start),
                Map("sex" -> "Male", "age" -> "33")
              )
            )
          } yield {
            r.groups shouldBe empty
          }
        }
      }

      "Eligible to test all criterion are met" in {
        withAlg { alg =>
          for {
            ab <- alg.create(
              fakeAb(
                userMetaCriteria = Some(
                  and(RegexMatch("sex", "Male|^M$"), RegexMatch("age", "^2\\d$"))
                )
              )
            )
            r <- alg.getGroupsWithMeta(
              q(
                randomUserId,
                Some(ab.data.start),
                Map("sex" -> "Male", "age" -> "23", "occupation" -> "engineer")
              )
            )
          } yield {
            r.groups.keys should contain(ab.data.feature)
          }
        }
      }

    }
  }

}
