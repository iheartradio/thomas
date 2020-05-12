package com.iheart.thomas

import java.time.{Instant, OffsetDateTime}
import java.util.concurrent.atomic.AtomicLong

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.iheart.thomas.abtest.{AssignGroups, TestsData}
import com.iheart.thomas.abtest.AssignGroups.MissingEligibilityInfo
import com.iheart.thomas.abtest.Error.CannotUpdateExpiredTest
import com.iheart.thomas.abtest.model.UserMetaCriterion.{ExactMatch, VersionRange}
import com.iheart.thomas.abtest.model.{UserGroupQuery, UserMetaCriterion}
import com.iheart.thomas.testkit.Factory.{now, _}
import org.scalatest.matchers.should.Matchers
import cats.implicits._

import concurrent.duration._
class AbtestAlgSuite extends AsyncIOSpec with Matchers {

  "AbtestAlg" - {

    "eligibility control" - {

      val algR = testkit.Resources.apis.map(_._3)
      val now = OffsetDateTime.now
      val anHourLater = Some(now.plusHours(1))

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
              t <- alg.create(
                fakeAb(
                  userMetaCriteria =
                    Some(UserMetaCriterion.and(ExactMatch("did", "aaa")))
                ),
                false
              )
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

      "AssignGroup" - {
        "assign with Missing Info for tests with eligibility control but Query missing info" in {
          implicit val nowF: IO[Instant] = testkit.Resources.defaultNowF
          algR
            .use { alg =>
              for {
                t <- alg.create(
                  fakeAb(
                    userMetaCriteria =
                      Some(UserMetaCriterion.and(ExactMatch("did", "aaa")))
                  ),
                  false
                )
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

    "updateUserMetaCriteria" - {
      object FastNowF {
        val count = new AtomicLong(0)

        implicit val fastNowF: IO[Instant] = IO { // a very fast clock
          val c = count.getAndIncrement()
          Instant.now.plusSeconds(c * 3600L)
        }
      }

      "Can 'update' test from the past using auto" in {

        import FastNowF._
        val algR = testkit.Resources.apis.map(_._3)

        algR
          .use { alg =>
            for {
              t <- alg.create(
                fakeAb(
                  userMetaCriteria = Some(
                    UserMetaCriterion.and(VersionRange("did", "1.2", Some("2.0")))
                  )
                ),
                false
              )
              updated <- alg.updateUserMetaCriteria(
                t._id,
                Some(UserMetaCriterion.and(VersionRange("did", "1.2", Some("3.0")))),
                true
              )

            } yield updated
          }
          .asserting { r =>
            r.data.userMetaCriteria shouldBe Some(
              UserMetaCriterion.and(VersionRange("did", "1.2", Some("3.0")))
            )

          }

      }

      "Cannot 'update' expired test from the past using auto" in {
        import FastNowF._

        val algR = testkit.Resources.apis.map(_._3)

        algR
          .use { alg =>
            for {
              t <- alg.create(
                fakeAb(end = 0),
                false
              )
              r <- alg
                .updateUserMetaCriteria(
                  t._id,
                  Some(UserMetaCriterion.and(ExactMatch("did", "bbb"))),
                  true
                )
                .as("fail")
                .recover {
                  case CannotUpdateExpiredTest(_) => "success"
                }

            } yield r
          }
          .asserting { r =>
            r shouldBe "success"

          }

      }

    }

  }

}
