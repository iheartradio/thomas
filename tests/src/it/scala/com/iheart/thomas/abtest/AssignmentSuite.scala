package com.iheart.thomas
package abtest

import java.time.OffsetDateTime

import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import cats.implicits._
import TestUtils._
import com.iheart.thomas.abtest.Error.EmptyUserId
import com.iheart.thomas.abtest.model.UserMetaCriterion._
import com.iheart.thomas.abtest.model._
import play.api.libs.json.Json

import scala.concurrent.duration.DurationInt

class AssignmentSuite extends AsyncIOSpec with Matchers {

  "getGroupMeta" - {
    "error out on if the user id is an empty string" in {
      withAlg(_.getGroupsWithMeta(UserGroupQuery(Some("")))).attempt
        .asserting {
          case Left(Error.ValidationErrors(d)) =>
            d.toList shouldBe List(EmptyUserId)
          case _ => fail
        }
    }

    "get groups for the test the user is in" in {
      val userId: UserId = randomUserId
      withAlg { alg =>
        for {
          test1 <- alg.create(fakeAb)
          test2 <- alg.create(fakeAb)
          retrieved <- alg.getGroupsWithMeta(q(userId, tomorrow))
        } yield {
          retrieved.groups.toList.size shouldBe 2

          retrieved.groups.keys.toSet shouldBe Set(
            test1.data.feature,
            test2.data.feature
          )
        }
      }
    }

    "get groups excluding the tests filtered by eligibility control filter" in {
      val test1 = fakeAb
      val test2 =
        fakeAb(userMetaCriteria =
          Some(UserMetaCriterion.and(UserMetaCriterion.ExactMatch("foo", "bar")))
        )
      withAlg { alg =>
        for {
          _ <- alg.create(test1)
          test2 <- alg.create(test2)
          retrieved <- alg.getGroupsWithMeta(
            q(
              randomUserId,
              tomorrow,
              Map("foo" -> "bar"),
              eligibilityControlFilter = EligibilityControlFilter.On
            )
          )
        } yield {
          retrieved.groups.keys.toList shouldBe List(test2.data.feature)
        }
      }
    }

    "get groups for the indefinite test the user is in " in {
      val userId: UserId = randomUserId
      withAlg { alg =>
        for {
          test1 <- alg.create(fakeAb.copy(end = None))
          r <- alg.getGroupsWithMeta(
            q(
              userId,
              Some(OffsetDateTime.now.plusYears(10))
            )
          )
        } yield {
          r.groups.toList.size shouldBe 1
          r.groups.contains(test1.data.feature) shouldBe true
        }
      }
    }

    "get no groups if there are no ongoing tests at the time" in {

      val ab = fakeAb.copy(end = Some(OffsetDateTime.now.plusDays(2)))
      val userId: UserId = randomUserId

      withAlg { alg =>
        for {
          _ <- alg.create(ab)
          r <- alg.getGroupsWithMeta(q(userId, Some(OffsetDateTime.now.plusDays(3))))
        } yield {
          r.groups shouldBe empty
        }
      }
    }

    "get no groups if the user does not have the tag required by the test" in {
      val ab = fakeAb.copy(requiredTags = List("English Speaking"))
      val userId: UserId = randomUserId

      withAlg { alg =>
        for {
          _ <- alg.create(ab)
          r <- alg.getGroupsWithMeta(q(userId, tomorrow).copy(tags = List("iPad")))
        } yield {
          r.groups shouldBe empty
        }
      }
    }

    "get groups if the user does have the tag required by the test" in {
      val ab = fakeAb.copy(requiredTags = List("English Speaking"))

      val userId: UserId = randomUserId

      withAlg { alg =>
        for {
          _ <- alg.create(ab)
          r <- alg.getGroupsWithMeta(
            q(userId, tomorrow).copy(tags = List("English Speaking"))
          )
        } yield {
          r.groups shouldNot be(empty)
        }
      }
    }

    "get no groups if the user does not have the tag required by the test - multiple tags" in {
      val ab = fakeAb.copy(requiredTags = List("English Speaking", "Feature N"))

      val userId: UserId = randomUserId

      withAlg { alg =>
        for {
          _ <- alg.create(ab)
          r <- alg.getGroupsWithMeta(q(userId, tomorrow).copy(tags = List("iPad")))
        } yield {
          r.groups shouldBe empty
        }
      }
    }

    "get groups if the user does have the tag required by the test - multiple tags" in {
      val ab = fakeAb.copy(requiredTags = List("English Speaking", "Feature N"))

      val userId: UserId = randomUserId
      withAlg { alg =>
        for {
          _ <- alg.create(ab)
          r <- alg.getGroupsWithMeta(
            q(userId, tomorrow).copy(tags = List("English Speaking", "Feature N"))
          )
        } yield {
          r.groups shouldNot be(empty)
        }

      }
    }

    "get groups if the user does have the tag required by the test - multiple tags separated with comma" in {
      val ab = fakeAb.copy(requiredTags = List("English Speaking", "Feature N"))
      val userId: UserId = randomUserId

      withAlg { alg =>
        for {
          _ <- alg.create(ab)
          r <- alg.getGroupsWithMeta(
            q(userId, tomorrow).copy(tags = List("English Speaking", "Feature N"))
          )
        } yield {
          r.groups.keys should contain(ab.feature)
        }
      }
    }

    "AlternativeId Integration" - {
      val n = 20
      val testUsingAlternativeId = fakeAb(
        groups = (1 to n).toList.map(i => group(s"Group$i", 1d / n.toDouble)),
        alternativeIdName = Some("deviceId")
      )
      val userIds = List.fill(30)(randomUserId)

      "use alternative id if AlternativeIdName is set and alternative Id is present in user meta" in {
        withAlg { alg =>
          for {
            _ <- alg.create(testUsingAlternativeId)
            r <- userIds.traverse { userId =>
              alg.getGroupsWithMeta(
                UserGroupQuery(
                  Some(userId),
                  at = Some(testUsingAlternativeId.start),
                  meta = Map(
                    "deviceId" -> "123"
                  )
                )
              )
            }
          } yield {
            val assignments =
              r.map(_.groups(testUsingAlternativeId.feature)).distinct
            assignments.size shouldBe 1 //since they have the same device id they should be assigned with the same group even though they have difference userIds

          }
        }
      }

      "do not participate if AlternativeIdName is set but alternative Id is not present in user meta" in {
        withAlg { alg =>
          for {
            _ <- alg.create(testUsingAlternativeId)
            r <- alg.getGroupsWithMeta(
              UserGroupQuery(
                Some(randomUserId),
                at = Some(testUsingAlternativeId.start),
                meta = Map()
              )
            )
          } yield {
            r.groups.contains(testUsingAlternativeId.feature) shouldBe false
          }
        }

      }

      "works with overrides" in {
        val deviceId = randomUserId
        withAlg { alg =>
          for {
            test <- alg.create(
              testUsingAlternativeId
                .copy(groups = List(group("A", 1), group("B", 0)))
            )
            _ <- alg.addOverrides(test.data.feature, Map(deviceId -> "B"))
            r <- alg.getGroupsWithMeta(
              UserGroupQuery(
                Some(randomUserId),
                at = Some(testUsingAlternativeId.start),
                meta = Map("deviceId" -> deviceId)
              )
            )
          } yield {
            r.groups(testUsingAlternativeId.feature) shouldBe "B"
          }
        }
      }

    }

    "Segment Range Integration" - {
      "two tests sharing the same range should have the same group of users" in {

        withAlg { alg =>
          for {
            _ <- alg.create(fakeAb(segRanges = List(GroupRange(0, 0.3))))
            ab <- alg.create(fakeAb(segRanges = List(GroupRange(0, 0.3))))

            userAssignments <-
              List
                .fill(500) {
                  alg.getGroupsWithMeta(q(randomUserId, at = Some(ab.data.start)))
                }
                .sequence
          } yield {
            userAssignments.map(_.groups.size).toSet shouldBe Set(0, 2)
            val countOfUsersInTests = userAssignments.count(_.groups.size == 2)
            countOfUsersInTests.toDouble / 500d shouldBe (0.3 +- 0.1)
          }
        }
      }

      "two tests having mutually exclusive ranges should have no overlaps of users" in {
        withAlg { alg =>
          for {
            _ <- alg.create(fakeAb(segRanges = List(GroupRange(0, 0.3))))
            ab <- alg.create(fakeAb(segRanges = List(GroupRange(0.30001, 0.5))))

            userAssignments <-
              List
                .fill(500) {
                  alg.getGroupsWithMeta(q(randomUserId, at = Some(ab.data.start)))
                }
                .sequence
          } yield {
            userAssignments.map(_.groups.size).toSet shouldBe Set(0, 1)
            val countOfUsersInTests =
              userAssignments.count(_.groups.keys.toList.contains(ab.data.feature))
            countOfUsersInTests.toDouble / 500d shouldBe (0.2 +- 0.1)
          }
        }
      }
    }

    "Reshuffle Integration" - {
      "reassign majority of the users" in {
        val originalSpec = fakeAb
        val userIds = List.fill(200)(randomUserId)
        withAlg { alg =>
          for {
            ab <- alg.create(originalSpec)
            userAssignments <- userIds.traverse { userId =>
              alg
                .getGroupsWithMeta(q(userId, at = Some(ab.data.start)))
                .map(r => (userId, r.groups(originalSpec.feature)))
            }
            reshuffled <- alg.continue(
              originalSpec
                .copy(start = originalSpec.start.plusDays(1), reshuffle = true)
            )
            assignmentChanged <- userAssignments.traverse {
              case (userId, assignment) =>
                alg
                  .getGroupsWithMeta(
                    q(userId, at = Some(reshuffled.data.start))
                  )
                  .map(r => r.groups(originalSpec.feature) != assignment)
            }
          } yield {
            val changeRatio = assignmentChanged
              .count(identity)
              .toDouble / userIds.size.toDouble
            changeRatio shouldBe >(0.4d)
          }

        }
      }

      "do not reassign already reshuffled tests" in {
        val originalSpec = fakeAb()
        val userIds = List.fill(200)(randomUserId)
        withAlg { alg =>
          for {
            _ <- alg.create(originalSpec)

            reshuffled <- alg.continue(
              originalSpec
                .copy(start = originalSpec.start.plusDays(1), reshuffle = true)
            )
            userAssignments <- userIds.traverse { userId =>
              alg
                .getGroupsWithMeta(q(userId, at = Some(reshuffled.data.start)))
                .map(r => (userId, r.groups(originalSpec.feature)))
            }

            continued <- alg.continue(
              (originalSpec
                .copy(start = originalSpec.start.plusDays(2), reshuffle = false))
            )
            assignmentChanged <- userAssignments.traverse {
              case (userId, assignment) =>
                alg
                  .getGroupsWithMeta(
                    q(userId, at = Some(continued.data.start))
                  )
                  .map(r => r.groups(originalSpec.feature) != assignment)
            }
          } yield {
            assignmentChanged.count(identity) shouldBe 0
          }

        }

      }
    }

    "group meta integration" - {
      "return meta together with user" in {
        val userIds = List.fill(20)(randomUserId)

        withAlg { alg =>
          for {
            ab <- alg.create(fakeAb(1, 20))
            _ <- alg.addGroupMetas(
              ab._id,
              Map("A" -> Json.obj("ff" -> "a"), "B" -> Json.obj("ff" -> "b")),
              false
            )
            assignments <- userIds.traverse { userId =>
              alg.getGroupsWithMeta(
                q(userId, at = Some(ab.data.start))
              )
            }
          } yield {
            val feature = ab.data.feature
            assignments.foreach { result =>
              result.groups.keys should contain(feature)
              result.metas.keys should contain(feature)
              result.metas(feature).value("ff").as[String] shouldBe result
                .groups(feature)
                .toLowerCase
            }
          }
        }

      }

      "Cannot change meta for test already started when auto is false" in {
        withAlg { alg =>
          for {
            ab <- alg.create(fakeAb.copy(start = OffsetDateTime.now))
            _ <- ioTimer.sleep(100.millis)
            _ <- alg.addGroupMetas(ab._id, Map("A" -> Json.obj("ff" -> "a")), false)
          } yield ()
        }.assertThrows[Error.CannotChangePastTest]
      }

      "Can change meta for test already started when auto is true" in {
        val meta = Map("A" -> Json.obj("ff" -> "a"), "B" -> Json.obj("ff" -> "b"))
        withAlg { alg =>
          for {
            ab <- alg.create(fakeAb.copy(start = OffsetDateTime.now))
            _ <- ioTimer.sleep(100.millis)
            _ <- alg.addGroupMetas(ab._id, meta, true)
            tests <- alg.getTestsByFeature(ab.data.feature)
          } yield {
            tests.size shouldBe 2
            tests.head.data.getGroupMetas shouldBe meta
          }
        }
      }

      "throw validation error when group name in meta does not exist in test" in {

        withAlg { alg =>
          for {
            ab <- alg.create(fakeAb(1))
            _ <- alg.addGroupMetas(
              ab._id,
              Map("NonexistingGroup" -> Json.obj("ff" -> "a")),
              false
            )
          } yield ()
        }.assertThrows[Error.ValidationErrors]

      }

      "pass validation when not all the groups in tests are mentioned in meta" in {
        withAlg { alg =>
          for {
            ab <- alg.create(fakeAb(1))
            _ <- alg.addGroupMetas(
              ab._id,
              Map("A" -> Json.obj("ff" -> "a")),
              false
            )
          } yield ()
        }.assertNoException
      }

      "does not return meta that doesn't come with one" in {
        withAlg { alg =>
          for {
            ab <- alg.create(fakeAb.copy(groups = List(group("A", 1))))
            g <- alg.getGroupsWithMeta(q(randomUserId, Some(ab.data.start)))
          } yield {
            g.metas.contains(ab.data.feature) shouldBe false
          }
        }
      }

      "subsequent test from spec remove meta settings when new spec has empty meta" in {
        val metas = Map("A" -> Json.obj("ff" -> "a"), "B" -> Json.obj("ff" -> "b"))

        withAlg { alg =>
          for {
            ab <- alg.create(
              fakeAb(
                1,
                20,
                groups = List(
                  Group("A", 0.5, metas.get("A")),
                  Group("B", 0.5, metas.get("B"))
                )
              )
            )

            subSequent <- alg.create(fakeAb(21, 25, feature = ab.data.feature))

          } yield {
            subSequent.data.getGroupMetas should be(empty)
          }
        }

      }

      "subsequent test from spec rewrite meta settings " in {

        val metas = Map("A" -> Json.obj("ff" -> "a"), "B" -> Json.obj("ff" -> "b"))
        val newMetas =
          Map("A" -> Json.obj("ff" -> "aa"), "B" -> Json.obj("ff" -> "bb"))

        withAlg { alg =>
          for {
            ab <- alg.create(
              fakeAb(
                1,
                20,
                groups = List(
                  Group("A", 0.5, metas.get("A")),
                  Group("B", 0.5, metas.get("B"))
                )
              )
            )

            subSequent <- alg.create(
              fakeAb(
                21,
                25,
                feature = ab.data.feature,
                groups = List(
                  Group("A", 0.5, newMetas.get("A")),
                  Group("B", 0.5, newMetas.get("B"))
                )
              )
            )

          } yield {
            subSequent.data.getGroupMetas shouldBe newMetas
          }
        }

      }
    }

    "overrides" - {
      "add an override to an existing test" in {
        val userIds = List.fill(20)(randomUserId)

        withAlg { alg =>
          for {
            ab <- alg.create(fakeAb)
            overrideGroup = ab.data.groups.last.name
            _ <- alg.addOverrides(
              ab.data.feature,
              userIds.map((_, overrideGroup)).toMap
            )
            assignments <- userIds.traverse { userId =>
              alg.getGroupsWithMeta(q(userId, Some(ab.data.start)))
            }
          } yield {
            assignments.map(_.groups(ab.data.feature)).toSet shouldBe Set(
              overrideGroup
            )
          }
        }

      }

      "override honor eligibility control" in {
        val userId = "1"
        withAlg { alg =>
          for {
            ab <- alg.create(
              fakeAb(groups = List(group("A", 0)), requiredTags = List("tag1"))
            )
            _ <- alg.addOverrides(ab.data.feature, Map(userId -> "A"))
            assignments <- alg.getGroupsWithMeta(q(userId, Some(ab.data.start)))
          } yield {
            assignments.groups.get(ab.data.feature) should be(empty)
          }
        }
      }

      "override overrides eligibility control when set so" in {
        val userId = "1"
        withAlg { alg =>
          for {
            ab <- alg.create(
              fakeAb(groups = List(group("A", 0)), requiredTags = List("tag1"))
            )
            _ <- alg.addOverrides(ab.data.feature, Map(userId -> "A"))
            _ <- alg.setOverrideEligibilityIn(ab.data.feature, true)
            assignments <- alg.getGroupsWithMeta(q(userId, Some(ab.data.start)))
          } yield {
            assignments.groups.get(ab.data.feature) shouldBe Some("A")
          }
        }
      }

      "retrieve meta according to overrides" in {

        val overrideGroup = "B"
        val userId = randomUserId
        withAlg { alg =>
          for {
            ab <- alg.create(
              fakeAb(1, 20).copy(
                groups = List(group("A", 1), group(overrideGroup, 0))
              )
            )
            _ <- alg.addGroupMetas(
              ab._id,
              Map(
                "A" -> Json.obj("ff" -> "a"),
                overrideGroup -> Json.obj("ff" -> "b")
              ),
              false
            )
            _ <- alg.addOverrides(ab.data.feature, Map(userId -> overrideGroup))

            assignments <- alg.getGroupsWithMeta(q(userId, Some(ab.data.start)))
          } yield {
            assignments.metas(ab.data.feature).value("ff").as[String] shouldBe "b"
          }
        }
      }

    }
  }
}
