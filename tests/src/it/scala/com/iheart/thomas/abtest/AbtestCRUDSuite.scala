package com.iheart.thomas.abtest

import java.time.{Instant, OffsetDateTime}
import cats.effect.testing.scalatest.AsyncIOSpec
import TestUtils.{fakeAb, _}
import cats.data.NonEmptyList
import cats.effect.IO
import lihua.{Entity, EntityId}
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AsyncFreeSpec

import concurrent.duration._
import cats.implicits._
import com.iheart.thomas.{FeatureName, UserId}
import com.iheart.thomas.abtest.model.{Abtest, Group, UserGroupQuery}
import play.api.libs.json.Json

class AbtestCRUDSuite extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  "AbtestAlg" - {
    "getTest" - {
      "return error when get abtest with nonexit Id" in {
        withAlg { alg =>
          alg.getTest(lihua.mongo.generateId)
        }.assertThrows[Error.NotFound]
      }
    }

    "getAllFeatures" - {

      "return all features" in {
        withAlg { alg =>
          for {
            _ <- alg.create(fakeAb(feature = "feature1"), false)
            _ <- alg.create(fakeAb(feature = "feature2"), false)
            fs <- alg.getAllFeatureNames
          } yield fs

        }.asserting(_ shouldBe List("feature1", "feature2"))
      }
    }

    "getTestsByFeature" - {
      "get test by feature name" in {
        withAlg { alg =>
          for {
            test1 <- alg.create(fakeAb(1, 2), false)
            test2 <- alg.create(fakeAb(3, 4, test1.data.feature), false)
            tests <- alg.getTestsByFeature(test1.data.feature)
            notFound <- alg.getTestsByFeature("mismatch")
          } yield (test1, test2, tests, notFound)
        }.asserting { case (test1, test2, tests, notFound) =>
          tests.toSet shouldBe Vector(test1, test2).toSet
          notFound shouldBe Vector.empty
        }
      }

      "get test by feature name sorted by start and end date" in {
        withAlg { alg =>
          for {
            test1 <- alg.create(fakeAb(1, 3))
            test2 <- alg.continue(fakeAb(2, 4, test1.data.feature))
            tests <- alg.getTestsByFeature(test1.data.feature)
          } yield (test1, test2, tests)
        }.asserting { case (test1, test2, tests) =>
          tests.map(_._id) shouldBe Vector(test2._id, test1._id)
        }
      }

      "get test by feature name sorted by end date missing" in {
        withAlg { alg =>
          for {
            test1 <- alg.create(fakeAb(1, 3))
            test2 <- alg.continue(fakeAb(2, 4, test1.data.feature).copy(end = None))
            tests <- alg.getTestsByFeature(test1.data.feature)
          } yield (test1, test2, tests)
        }.asserting { case (test1, test2, tests) =>
          tests.map(_._id) shouldBe Vector(test2._id, test1._id)
        }
      }
    }

    "getTestsData" - {

      "get tests valid at the target time" in {
        withAlg { alg =>
          for {
            test <- alg.create(fakeAb())
            testData <- alg.getTestsData(
              Instant.now.plusMinutes(1),
              None
            )
          } yield (test, testData)
        }.asserting { case (test, testData) =>
          testData.data.map(_._1) shouldBe Vector(test)

        }

      }

      "get tests valid within range of the target time" in {
        withAlg { alg =>
          for {
            test <- alg.create(fakeAb())
            td <- alg.getTestsData(Instant.now.minusMinutes(1), Some(4.minutes))

          } yield (test, td)
        }.asserting { case (test, td) =>
          td.data.map(_._1) shouldBe Vector(test)
        }
      }

      "get tests must return range" in {
        withAlg { alg =>
          for {
            _ <- alg.create(fakeAb())
            td <- alg.getTestsData(Instant.now.minusMinutes(1), Some(4.minutes))

          } yield td
        }.asserting { _.duration.map(_.toMillis) shouldBe Some(4.minutes.toMillis) }
      }

      "does not get tests valid outside range of the target time" in {
        withAlg { alg =>
          for {
            _ <- alg.create(fakeAb())
            td <- alg.getTestsData(Instant.now.minusMinutes(6), Some(4.minutes))

          } yield td
        }.asserting { _.data shouldBe empty }
      }

      "does not get tests valid at the target time" in {
        withAlg { alg =>
          for {
            _ <- alg.create(fakeAb())
            td <- alg.getTestsData(Instant.now.minusMinutes(1), None)

          } yield td
        }.asserting { _.data shouldBe empty }

      }
    }

    "create" - {
      "creates and retrieves when the data is valid" in {
        val ab = fakeAb()
        withAlg { alg =>
          for {
            created <- alg.create(ab)
            retrieved <- alg.getTest(created._id)
          } yield {
            created.data.name shouldBe ab.name
            retrieved.data.name shouldBe ab.name
          }
        }
      }

      "return validation errors when creating a test that is invalid" in {

        withAlg(
          _.create(
            fakeAb(
              start = -3,
              end = -4,
              groups = List(group("A", 0.2), group("B", 0.7), group("C", 0.2)),
              feature = "invalid feature with space"
            ),
            false
          )
        ).attempt.asserting {
          case Left(Error.ValidationErrors(details)) =>
            details.toList.toSet shouldBe Set(
              Error.InconsistentGroupSizes(List(0.2, 0.7, 0.2)),
              Error.InconsistentTimeRange,
              Error.CannotScheduleTestBeforeNow,
              Error.InvalidFeatureName
            )

          case _ => fail("did not raise expected error")
        }
      }

      "cannot schedule a test that starts before the last test ends" in {
        withAlg(alg =>
          alg.create(
            fakeAb(5, 10, feature = "feature1"),
            false
          ) >> alg.create(fakeAb(1, 2, feature = "feature1"))
        ).assertThrows[Error.ConflictTest]

      }

      "succeeds when creating a test without time conflict with an existing test on the same feature" in {
        val first = fakeAb()
        withAlg(alg =>
          alg.create(first) >>
            alg.create(
              fakeAb().copy(
                feature = first.feature,
                start = first.end.get.plusDays(1),
                end = first.end.map(_.plusDays(200))
              ),
              false
            )
        ).assertNoException
      }

      "auto create a test when no conflict" in {
        val spec = fakeAb()
        withAlg(_.create(spec, true)).asserting(_.data.name shouldBe spec.name)
      }

      "auto update the conflict test if there is no group change" in {
        val featureName = "A_new_big_feature"
        val ab = fakeAb(2, 5, feature = featureName)
        withAlg { alg =>
          for {
            existing <- alg.create(fakeAb(1, 3, feature = featureName))
            updated <- alg.create(ab, true)
          } yield {
            updated.data shouldBe existing.data
              .copy(start = ab.startI, end = ab.endI)
            updated._id shouldBe existing._id
          }
        }

      }

      "auto continue a test with a conflict when there is a group change" in {
        val featureName = "A_new_big_feature"

        val ab = fakeAb(
          2,
          5,
          feature = featureName,
          groups = List(group("A", 0.7), group("B", 0.3))
        )

        withAlg { alg =>
          for {
            conflict <- alg.create(fakeAb(1, 3, feature = featureName))
            _ <- alg.create(ab, true)
            retrieved <- alg.getTest(conflict._id)
          } yield {
            retrieved.data.end shouldBe Some(ab.startI)
          }
        }
      }

      "auto delete all tests that scheduled after this one if group is different" in {
        val featureName = "A_new_big_feature_2"
        val ab = fakeAb(
          1,
          5,
          feature = featureName,
          groups = List(group("A", 0.7), group("B", 0.3))
        )

        withAlg { alg =>
          for {
            conflict1 <- alg.create(fakeAb(2, 3, feature = featureName))
            conflict2 <- alg.create(fakeAb(4, 6, feature = featureName))
            attempt1 <- alg.create(ab, true).attempt
            attempt2 <- alg.create(ab, true)
            retrieve1 <- alg.getTest(conflict1._id).attempt
            retrieve2 <- alg.getTest(conflict2._id).attempt
          } yield {
            attempt1 shouldBe Left(Error.ConflictTest(conflict1))

            attempt2.data.name shouldBe ab.name

            retrieve1.left.get shouldBe a[Error.NotFound]
            retrieve2.left.get shouldBe a[Error.NotFound]
          }
        }
      }

    }

    "continue" - {
      "continue a test" in {
        val test1 =
          fakeAb(0, 100, "feature1").copy(
            groups = List(group("A", 0.2), group("B", 0.4))
          )

        val test2 =
          fakeAb(1, 100, "feature1").copy(
            groups = List(group("A", 0.6), group("B", 0.4))
          )

        withAlg { alg =>
          for {
            test1Created <- alg.create(test1)
            test2Created <- alg.continue(test2)
            test1Found <- alg.getTest(test1Created._id)
            test2Found <- alg.getTest(test2Created._id)
          } yield {
            test1Found.data.end shouldBe Some(test2.startI)

            test2Found.data.groups shouldBe List(
              group("A", 0.6),
              group("B", 0.4)
            )
          }

        }
      }

      "throw not found there is no test to continue from" in {
        withAlg(_.continue(fakeAb)).assertThrows[Error.NotFound]
      }

      "return validation error when the schedule start is after the last test end" in {
        val test1 = fakeAb(0, 1)

        val test2 = fakeAb(2, 100, test1.feature)

        withAlg { alg =>
          alg.create(test1) >> alg.continue(test2)
        }.attempt.asserting {
          case Left(Error.ValidationErrors(d)) =>
            d.size shouldBe 1
            d.head shouldBe a[Error.ContinuationGap]
          case _ => fail
        }

      }

      "return validation error when the schedule start is before the last test start" in {
        val test1 = fakeAb(2, 46, "feature1")
        val test2 = fakeAb(1, 100, "feature1")

        withAlg { alg =>
          alg.create(test1) >> alg.continue(test2)
        }.attempt.asserting {
          case Left(Error.ValidationErrors(d)) =>
            d.size shouldBe 1
            d.head shouldBe a[Error.ContinuationBefore]
          case _ => fail
        }
      }
    }

    "getAllTests" - {

      "get all tests" in {
        withAlg { alg =>
          for {
            _ <- alg.create(fakeAb)
            _ <- alg.create(fakeAb)
            tests <- alg.getAllTests(Some(OffsetDateTime.now.plusDays(1)))
          } yield tests

        }.asserting(_.size shouldBe 2)
      }
    }

    "getAllTestsEndAfter" - {

      "get all tests ends after date" in {
        val dayAfterTomorrow = OffsetDateTime.now.plusDays(2).toEpochSecond
        withAlg { alg =>
          for {
            ab <- alg.create(fakeAb(end = 100))
            _ <- alg.create(fakeAb(end = 1))
            r <- alg.getAllTestsEndAfter(dayAfterTomorrow)
          } yield {
            r.size shouldBe 1
            r.head.data shouldBe ab.data
          }
        }
      }

    }

    "delete" - {
      "terminate a test before expires" in {
        val userId: UserId = randomUserId
        val asOf = Some(OffsetDateTime.now.plusHours(1))
        withAlg { alg =>
          for {
            test <- alg.create(fakeAb)
            r1 <- alg.getGroupsWithMeta(q(userId, asOf))
            _ <- alg.terminate(test._id)
            r2 <- alg.getGroupsWithMeta(q(userId, asOf))
            retrieved <- alg.getTest(test._id)
          } yield {
            r1.groups.toList.size shouldBe 1
            r2.groups shouldBe empty
            retrieved.data.end.get
              .isBefore(Instant.now.plusSeconds(1)) shouldBe true
          }
        }
      }

      "delete a test if it has not started yet" in {

        val userId: UserId = randomUserId

        val asOf = Some(OffsetDateTime.now.plusHours(1))

        withAlg { alg =>
          for {
            test <- alg.create(fakeAb(1, 100))
            _ <- alg.terminate(test._id)
            r <- alg.getGroupsWithMeta(q(userId, asOf))
            retrieved <- alg.getTest(test._id).attempt
          } yield {
            r.groups shouldBe empty
            retrieved.left.get shouldBe a[Error.NotFound]
          }
        }
      }

      "do nothing if a test already expired" in {
        withAlg { alg =>
          for {
            test <- alg.create(
              fakeAb.copy(end = Some(OffsetDateTime.now.plusNanos(30000000)))
            )
            _ <- IO.sleep(50.millis)
            _ <- alg.terminate(test._id)
            r <- alg.getTest(test._id)
          } yield {
            r shouldBe test
          }
        }
      }

    }

    "updateUserMetaCriteria" - {
      import com.iheart.thomas.abtest.model.UserMetaCriterion._

      "delete userMetaCriteria if there is no body" in {
        withAlg { alg =>
          for {
            ab <- alg.create(
              fakeAb(start = 1, userMetaCriteria = Some(and(RegexMatch("a", "a"))))
            )
            _ <- alg.updateUserMetaCriteria(ab._id, None, false)
            retrieved <- alg.getTest(ab._id)
          } yield {

            retrieved.data.userMetaCriteria shouldBe None
          }
        }

      }

      "update userMetaCriteria" in {
        withAlg { alg =>
          for {
            ab <- alg.create(fakeAb(start = 1))
            updated <- alg.updateUserMetaCriteria(
              ab._id,
              Some(and(RegexMatch("a", "a"))),
              false
            )
          } yield {
            updated.data.userMetaCriteria shouldBe Some(and(RegexMatch("a", "a")))
          }
        }

      }

    }

    "delete overrides" - {
      "remove an override to an existing test" in {
        val userId1 = randomUserId
        val userId2 = randomUserId

        withAlg { alg =>
          for {
            ab <- alg.create(
              fakeAb(1, 2, feature = "a_new_feature_to_override")
            )
            overrideGroup = ab.data.groups.last.name
            _ <- alg.addOverrides(
              ab.data.feature,
              Map(userId1 -> overrideGroup, userId2 -> overrideGroup)
            )

            _ <- alg.removeOverrides(ab.data.feature, userId2)
            retrievedOverridesAfterRemoval <- alg.getOverrides(ab.data.feature)

          } yield {
            retrievedOverridesAfterRemoval.overrides shouldBe Map(
              userId1 -> overrideGroup
            )
          }
        }
      }

      "remove all overrides an existing test" in {
        val userId1 = randomUserId
        val userId2 = randomUserId

        withAlg { alg =>
          for {
            ab <- alg.create(
              fakeAb(1, 2, feature = "a_new_feature_to_override")
            )
            overrideGroup = ab.data.groups.last.name
            _ <- alg.addOverrides(
              ab.data.feature,
              Map(userId1 -> overrideGroup, userId2 -> overrideGroup)
            )

            _ <- alg.removeAllOverrides(ab.data.feature)
            retrievedOverridesAfterRemoval <- alg.getOverrides(ab.data.feature)

          } yield {
            retrievedOverridesAfterRemoval.overrides should be(empty)
          }
        }

      }
    }

    "new group meta" - {
      "reserve group metas on round trips" in {
        val metas = Map("A" -> Json.obj("ff" -> "a"), "B" -> Json.obj("ff" -> "b"))
        val initSpec = fakeAb(groups =
          List(Group("A", 0.5, metas.get("A")), Group("B", 0.5, metas.get("B")))
        )

        withAlg { alg =>
          for {
            init <- alg.create(initSpec)
            retried <- alg.getTest(init._id)
            continue <- alg.create(
              retried.data.toSpec.copy(start = OffsetDateTime.now.plusSeconds(2)),
              true
            )
            continueRetried <- alg.getTest(continue._id)
          } yield {
            init.data.getGroupMetas shouldBe metas
            retried.data.getGroupMetas shouldBe metas

            continueRetried._id should not be (init._id)

            continueRetried.data.getGroupMetas shouldBe metas

          }

        }
      }
    }
  }
}
