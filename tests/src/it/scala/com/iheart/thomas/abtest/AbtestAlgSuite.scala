package com.iheart.thomas
package abtest

import java.time.{Instant, OffsetDateTime}
import java.util.concurrent.atomic.AtomicLong
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.iheart.thomas.abtest.Error.{
  CannotChangeGroupSizeWithFollowUpTest,
  CannotUpdateExpiredTest,
  ConflictTest
}
import com.iheart.thomas.abtest.model.UserMetaCriterion.{ExactMatch, VersionRange}
import com.iheart.thomas.abtest.model.{
  EligibilityControlFilter,
  UserGroupQuery,
  UserMetaCriterion
}
import com.iheart.thomas.testkit.Factory.{now, _}
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AsyncFreeSpec
import cats.implicits._
import utils.time._
import cats.MonadError

import concurrent.duration._
class AbtestAlgSuite extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  val F = MonadError[IO, Throwable]

  "AbtestAlg" - {
    val algR = testkit.Resources.apis.map(_._2)

    "update test flow" - {
      "does not allow changing group sizes when there is a follow test" in {
        algR
          .use { alg =>
            for {
              init <- alg.create(fakeAb(1, 2), false)
              _ <- alg.create(
                fakeAb().copy(
                  feature = init.data.feature,
                  start = init.data.end.get.plusSeconds(10).toOffsetDateTimeUTC
                ),
                false
              )
              tryUpdate <-
                alg
                  .updateTest(
                    init._id,
                    init.data.toSpec.copy(
                      groups = init.data.groups.map(g => g.copy(size = g.size * 0.5))
                    )
                  )
                  .attempt

            } yield tryUpdate
          }
          .asserting {
            case Left(CannotChangeGroupSizeWithFollowUpTest(_)) => succeed
            case Left(e)  => fail(s"incorrect error $e")
            case Right(_) => fail("Failed to prevent size change")
          }

      }

      "does not allow changing the end of a test that creates an overlap with the next test" in {
        algR
          .use { alg =>
            for {
              init <- alg.create(fakeAb(1, 2), false)
              _ <- alg.create(
                fakeAb().copy(
                  feature = init.data.feature,
                  start = init.data.end.get.plusSeconds(10).toOffsetDateTimeUTC
                ),
                false
              )
              tryUpdate <-
                alg
                  .updateTest(
                    init._id,
                    init.data.toSpec.copy(
                      end =
                        Some(init.data.end.get.plusSeconds(12).toOffsetDateTimeUTC)
                    )
                  )
                  .attempt

            } yield tryUpdate
          }
          .asserting {
            case Left(ConflictTest(_)) => succeed
            case Left(e)               => fail(s"incorrect error $e")
            case Right(_)              => fail("Failed to prevent overlap")
          }
      }

      "does not allow changing the end of a test that creates an overlap with the previous test" in {
        algR
          .use { alg =>
            for {
              init <- alg.create(fakeAb(1, 2), false)
              second <- alg.create(
                fakeAb(3, 4).copy(feature = init.data.feature),
                false
              )
              tryUpdate <-
                alg
                  .updateTest(
                    second._id,
                    second.data.toSpec.copy(
                      start = init.data.end.get.minusSeconds(12).toOffsetDateTimeUTC
                    )
                  )
                  .attempt

            } yield tryUpdate
          }
          .asserting {
            case Left(ConflictTest(_)) => succeed
            case Left(e)               => fail(s"incorrect error $e")
            case Right(_)              => fail("Failed to prevent overlap")
          }
      }

      "allows changing a test's trivial with follow up test" in {
        algR
          .use { alg =>
            for {
              init <- alg.create(fakeAb(1, 2), false)
              _ <- alg.create(
                fakeAb().copy(
                  feature = init.data.feature,
                  start = init.data.end.get.plusSeconds(10).toOffsetDateTimeUTC
                ),
                false
              )

              updated <- alg.updateTest(
                init._id,
                init.data.toSpec.copy(
                  author = "new author"
                )
              )

            } yield updated
          }
          .asserting(_.data.author shouldBe "new author")

      }

    }

    "eligibility control" - {

      val algR = testkit.Resources.apis.map(_._2)
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
          .asserting { case (r, t) =>
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
                  eligibilityControlFilter = EligibilityControlFilter.Off
                )
              )
            } yield (r, t)
          }
          .asserting { case (r, t) =>
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
                    eligibilityControlFilter = EligibilityControlFilter.Off
                  ),
                  1.day
                )
              } yield r
            }
            .asserting { _ should be(empty) }

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
        val algR = testkit.Resources.apis.map(_._2)

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

        val algR = testkit.Resources.apis.map(_._2)

        algR
          .use { alg =>
            for {
              t <- alg.create(
                fakeAb(end = 0),
                false
              )
              r <-
                alg
                  .updateUserMetaCriteria(
                    t._id,
                    Some(UserMetaCriterion.and(ExactMatch("did", "bbb"))),
                    true
                  )
                  .as("fail")
                  .recover { case CannotUpdateExpiredTest(_) =>
                    "success"
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
