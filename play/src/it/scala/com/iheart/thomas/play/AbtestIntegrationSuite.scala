package com.iheart.thomas
package play

import java.time.temporal.ChronoUnit
import java.time.{Instant, OffsetDateTime, ZoneOffset}

import cats.data.EitherT
import cats.effect.IO
import abtest._
import model._
import com.iheart.thomas.abtest.json.play.Formats._
import lihua.{Entity, EntityDAO, EntityId}
import org.scalatest.BeforeAndAfter
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import _root_.play.api.mvc.{Action, ControllerComponents, Request, Result}
import _root_.play.api.test.FakeRequest
import _root_.play.api.test.Helpers.status
import org.scalatestplus.play._
import com.iheart.thomas.analysis.DistributionSpec.Normal
import com.iheart.thomas.analysis._
import _root_.play.api.libs.json.{JsObject, Json, Writes}
import _root_.play.api.test.Helpers._
import com.iheart.thomas.abtest.model.UserMetaCriterion.{ExactMatch, RegexMatch}
import com.typesafe.config.ConfigFactory
import lihua.mongo.JsonFormats._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random
import UserMetaCriterion.and

class AbtestIntegrationSuite extends AbtestIntegrationSuiteBase {

  "GET test" should {
    "get test by id should return 404 if not in DB" in {
      val retrieve = controller.get(lihua.mongo.generateId)(FakeRequest())
      status(retrieve) mustBe NOT_FOUND
    }
  }

  "GET features" should {
    "return all features of all tests" in {
      createAbtestOnServer(fakeAb(1, 2, feature = "feature1"))
      createAbtestOnServer(fakeAb(3, 4, feature = "feature2"))
      val result = controller.getAllFeatures(FakeRequest())
      contentAsJson(result).as[List[FeatureName]] mustBe List("feature1", "feature2")
    }
  }

  "GET features/:feature/tests" should {
    "get test by feature name" in {
      val test1 = createAbtestOnServer(fakeAb(1, 2))
      val test2 = createAbtestOnServer(fakeAb(3, 4, test1.data.feature))
      val found = controller.getByFeature(test1.data.feature)(FakeRequest())
      contentAsJson(found).as[List[Entity[Abtest]]] mustBe List(test2, test1)

      val notFound = controller.getByFeature("mismatch")(FakeRequest())
      contentAsJson(notFound).as[List[Entity[Abtest]]] mustBe empty

    }

    "get test by feature name sorted by start and end date" in {
      val startTime = OffsetDateTime.now.plusDays(1)
      val test1 = createAbtestOnServer(fakeAb().copy(start = startTime))
      val test2 = createAbtestOnServer(
        Some(
          fakeAb(feature = test1.data.feature, groups = List(Group("A", 0.2)))
            .copy(start = startTime)
        ),
        auto = true
      )

      val found = controller.getByFeature(test1.data.feature)(FakeRequest())
      contentAsJson(found).as[List[Entity[Abtest]]].map(_._id) mustBe List(
        test2,
        test1
      ).map(_._id)
    }

    "get test by feature name sorted by end date missing" in {
      val startTime = OffsetDateTime.now.plusDays(1)
      val test1 = createAbtestOnServer(fakeAb().copy(start = startTime))
      val test2 = createAbtestOnServer(
        Some(
          fakeAb(feature = test1.data.feature, groups = List(Group("A", 0.2)))
            .copy(start = startTime, end = None)
        ),
        auto = true
      )

      val found = controller.getByFeature(test1.data.feature)(FakeRequest())
      contentAsJson(found).as[List[Entity[Abtest]]].map(_._id) mustBe List(
        test2,
        test1
      ).map(_._id)
    }
  }

  "GET /testsData" should {
    "get tests valid at the target time" in {
      val test = createAbtestOnServer(fakeAb())
      val resp = controller.getTestsData(
        Instant.now.plusMinutes(1).toEpochMilli,
        None
      )(FakeRequest())
      status(resp) mustBe OK
      contentAsJson(resp).as[TestsData].data.map(_._1) mustBe Vector(test)
    }

    "get tests valid within range of the target time" in {
      val test = createAbtestOnServer(fakeAb())
      val resp = controller.getTestsData(
        Instant.now.minusMinutes(1).toEpochMilli,
        Some(4.minutes.toMillis)
      )(FakeRequest())
      status(resp) mustBe OK
      contentAsJson(resp).as[TestsData].data.map(_._1) mustBe Vector(test)
    }

    "get tests must return range" in {
      val test = createAbtestOnServer(fakeAb())
      val resp = controller.getTestsData(
        Instant.now.minusMinutes(1).toEpochMilli,
        Some(4.minutes.toMillis)
      )(FakeRequest())
      status(resp) mustBe OK
      contentAsJson(resp).as[TestsData].duration.map(_.toMillis) mustBe Some(
        4.minutes.toMillis
      )
    }

    "does not get tests valid outside range of the target time" in {
      val test = createAbtestOnServer(fakeAb())
      val resp = controller.getTestsData(
        Instant.now.minusMinutes(6).toEpochMilli,
        Some(4.minutes.toMillis)
      )(FakeRequest())
      status(resp) mustBe OK
      contentAsJson(resp).as[TestsData].data.map(_._1) mustBe empty
    }

    "does not get tests valid at the target time" in {
      createAbtestOnServer(fakeAb())
      val resp = controller.getTestsData(
        Instant.now.minusMinutes(1).toEpochMilli,
        None
      )(FakeRequest())
      status(resp) mustBe OK
      contentAsJson(resp).as[TestsData].data mustBe empty
    }
  }

  "POST /tests" should {
    "create a test when it's valid" in {

      val ab = fakeAb()
      val creation = create(ab)

      status(creation) mustBe OK
      contentType(creation) mustBe Some("application/json")
      val created = contentAsJson(creation).as[Entity[Abtest]]

      created.data.name mustBe ab.name

      val retrieve = controller.get(created._id)(FakeRequest())

      status(retrieve) mustBe OK
      val retrieved = contentAsJson(retrieve).as[Entity[Abtest]]
      retrieved.data.name mustBe ab.name

    }

    "return validation errors when creating a test that is invalid" in {
      val ab = fakeAb()
      val invalidAB = fakeAb(
        start = -3,
        end = -4,
        groups = Group("C", 0.2) :: ab.groups,
        feature = "invalid feature with space"
      )

      val creation = create(invalidAB)

      status(creation) mustBe BAD_REQUEST
      val errors = (contentAsJson(creation) \ "errors").as[List[String]]
      errors.size mustBe 4
      errors.exists(_.contains("group sizes")) mustBe true
      errors.exists(_.contains("end after start")) mustBe true
      errors.exists(_.contains("starts in the past")) mustBe true
      errors.exists(_.contains("Feature name")) mustBe true

    }

    "cannot schedule a test that starts before the last test ends" in {

      val first = fakeAb(5, 10, feature = "feature1")

      status(create(first)) mustBe OK

      //a test that overlaps with the first test on the same feature
      val second = fakeAb(1, 2, "feature1")

      val result = create(second)
      status(result) mustBe CONFLICT

    }

    "succeeds when creating a test without time conflict with an existing test on the same feature" in {

      val first = fakeAb

      status(create(first)) mustBe OK

      //a test that overlaps with the first test on the same feature
      val second = fakeAb.copy(
        feature = first.feature,
        start = first.end.get.plusDays(1),
        end = first.end.map(_.plusDays(200))
      )

      val result = create(second)
      status(result) mustBe OK
    }

    "auto create a test when no conflict" in {

      val ab = fakeAb
      val creation = create(ab, true)

      status(creation) mustBe OK
      val created = contentAsJson(creation).as[Entity[Abtest]]
      created.data.name mustBe ab.name
    }

    "auto update the conflict test if there is no group change" in {
      val featureName = "A_new_big_feature"

      val existing = createAbtestOnServer(fakeAb(1, 3, feature = featureName))

      val ab = fakeAb(2, 5, feature = featureName)

      val result = create(ab, true)

      status(result) mustBe OK
      val updated = contentAsJson(result).as[Entity[Abtest]]

      updated.data mustBe existing.data.copy(start = ab.startI, end = ab.endI)
      updated._id mustBe existing._id
    }

    "auto continue a test with a conflict when there is a group change" in {
      val featureName = "A_new_big_feature"
      val conflict = createAbtestOnServer(fakeAb(1, 3, feature = featureName))

      val ab = fakeAb(
        2,
        5,
        feature = featureName,
        groups = List(Group("A", 0.7), Group("B", 0.3))
      )

      val creation = create(ab, true)

      status(creation) mustBe OK

      val old = getTestFromServer(conflict._id)
      old.data.end mustBe Some(ab.startI)
    }

    "auto delete all tests that scheduled after this one if group is different" in {
      val featureName = "A_new_big_feature_2"
      val conflict1 = createAbtestOnServer(fakeAb(2, 3, feature = featureName))
      val conflict2 = createAbtestOnServer(fakeAb(4, 6, feature = featureName))

      val ab = fakeAb(
        1,
        5,
        feature = featureName,
        groups = List(Group("A", 0.7), Group("B", 0.3))
      )

      val attempt1 = create(ab, true)

      status(attempt1) mustBe CONFLICT //first attempt still fails since there are two conflicting tests

      val attempt2 = create(ab, true)

      status(attempt2) mustBe OK

      status(controller.get(conflict1._id)(FakeRequest())) mustBe NOT_FOUND
      status(controller.get(conflict2._id)(FakeRequest())) mustBe NOT_FOUND
    }

    def verifyCount(
        fn: FeatureName,
        count: Int
      ) = {
      val r = toServer(controller.getByFeature(fn))
      contentAsJson(r).as[Vector[Entity[Abtest]]].size mustBe count
    }

    "Cannot create two tests for a new feature simultaneously" in {
      (0 to 30).foreach { _ =>
        val ab = fakeAb(1, 5)

        List(create(ab), create(ab)).map(status)

        verifyCount(ab.feature, 1)
      }

    }

    "Cannot create two tests for an existing feature simultaneously" in {
      (0 to 30).foreach { _ =>
        val ab = fakeAb(1, 2)
        val ab2 = fakeAb(3, 7, feature = ab.feature)

        createAbtestOnServer(ab)

        List(create(ab2), create(ab2)).map(status)

        verifyCount(ab.feature, 2)
      }
    }

    "Two attempts to auto create a test should end up with one test" in {
      (0 to 30).foreach { _ =>
        val ab = fakeAb(1, 5)
        val ab2 = fakeAb(3, 7, feature = ab.feature)

        createAbtestOnServer(ab)
        List(create(ab2, auto = true), create(ab2, auto = true)).map(status)

        val r = toServer(controller.getByFeature(ab.feature))
        contentAsJson(r)
          .as[Vector[Entity[Abtest]]]
          .filter(_.data.end == ab2.endI)
          .size mustBe 1
      }
    }

    "Cannot have two attempts of continuing a test simultaneously" in {
      (0 to 30).foreach { _ =>
        val ab = fakeAb(1)
        val ab2 = fakeAb(3, 6, feature = ab.feature)
        val ab3 = fakeAb(8, 9, feature = ab.feature)

        createAbtestOnServer(ab)
        List(
          controller.continue(jsonRequest(ab2)),
          controller.continue(jsonRequest(ab3))
        ).map(status)

        verifyCount(ab.feature, 2)
      }
    }

  }

  "PUT /tests" should {
    "continue a test" in {
      val test1 =
        fakeAb(0, 100, "feature1").copy(
          groups = List(Group("A", 0.2), Group("B", 0.4))
        )
      val test1Created = createAbtestOnServer(test1)

      val test2 =
        fakeAb(1, 100, "feature1").copy(
          groups = List(Group("A", 0.6), Group("B", 0.4))
        )

      val r = controller.continue(jsonRequest(test2))
      status(r) mustBe OK

      val test2Created = contentAsJson(r).as[Entity[Abtest]]

      val test1Found =
        contentAsJson(controller.get(test1Created._id)(FakeRequest()))
          .as[Entity[Abtest]]
      test1Found.data.end mustBe Some(test2.startI)
      val test2Found =
        contentAsJson(controller.get(test2Created._id)(FakeRequest()))
          .as[Entity[Abtest]]

      test2Found.data.groups mustBe List(Group("A", 0.6), Group("B", 0.4))

    }

    "return 404 a test when there is no test to continue from" in {

      val test = fakeAb
      val r = controller.continue(jsonRequest(test))
      status(r) mustBe NOT_FOUND
    }

    "return validation error when the schedule start is after the last test end" in {
      val test1 = fakeAb(0, 1, "feature1")
      createAbtestOnServer(test1)

      val test2 = fakeAb(2, 100, "feature1")

      val r = controller.continue(jsonRequest(test2))
      status(r) mustBe BAD_REQUEST
      val errors = (contentAsJson(r) \ "errors").as[List[String]]
      errors.size mustBe 1

      errors.exists(_.contains("Cannot schedule a continuation")) mustBe true
    }

    "return validation error when the schedule start is before the last test start" in {
      val test1 = fakeAb(2, 46, "feature1")
      createAbtestOnServer(test1)

      val test2 = fakeAb(1, 100, "feature1")

      val r = controller.continue(jsonRequest(test2))
      status(r) mustBe BAD_REQUEST
      val errors = (contentAsJson(r) \ "errors").as[List[String]]
      errors.size mustBe 1

      errors.exists(_.contains("Cannot schedule a continuation")) mustBe true
    }
  }

  "GET all tests" should {
    "get all tests" in {
      status(create(fakeAb)) mustBe OK
      status(create(fakeAb.copy(feature = "another_feature"))) mustBe OK

      val result = controller.getAllTests(tomorrow, None)(FakeRequest())
      status(result) mustBe OK
      contentAsJson(result).as[List[Entity[Abtest]]].size mustBe 2
    }

    "get all tests ends after date" in {
      val ab = createAbtestOnServer(fakeAb(end = 100))
      createAbtestOnServer(fakeAb(start = 0, end = 1))

      val dayAfterTomorrow = Some(OffsetDateTime.now.plusDays(2).toEpochSecond)
      val result = controller.getAllTests(None, dayAfterTomorrow)(FakeRequest())
      status(result) mustBe OK
      val tests = contentAsJson(result).as[List[Entity[Abtest]]]
      tests.size mustBe 1
      tests.head.data mustBe ab.data

    }

    "returns error if both at and endBefore are passed ins" in {
      val result = controller.getAllTests(tomorrow, tomorrow)(FakeRequest())
      status(result) mustBe BAD_REQUEST
    }
  }

  "GET groups" should {
    "error out if the user id is an empty string" in {
      val resp = controller.getGroups("", tomorrow)(FakeRequest())
      status(resp) mustBe BAD_REQUEST
      val errors = (contentAsJson(resp) \ "errors").as[List[String]]
      errors.exists(_.contains("User id cannot be an empty string")) mustBe true
    }

    "error out on getGroupWithMeta if the user id is an empty string" in {
      val resp =
        controller.getGroupsWithMeta(
          jsonRequest(UserGroupQuery(Some("")))
        )
      status(resp) mustBe BAD_REQUEST
      val errors = (contentAsJson(resp) \ "errors").as[List[String]]
      errors.exists(_.contains("User id cannot be an empty string")) mustBe true
    }

    "get groups for the test the user is in" in {

      val test1 = createAbtestOnServer().data
      val test2 = createAbtestOnServer().data
      val userId: UserId = randomUserId

      val groups = controller.getGroups(userId, tomorrow)(FakeRequest())
      status(groups) mustBe OK
      val retrieved = contentAsJson(groups).as[Map[FeatureName, GroupName]]
      retrieved.toList.size mustBe 2

      retrieved.contains(test1.feature) mustBe true
      retrieved.contains(test2.feature) mustBe true
    }

    "get groups for the indefinite test the user is in " in {

      val test1 = createAbtestOnServer(fakeAb.copy(end = None)).data
      val userId: UserId = randomUserId

      val groups = controller.getGroups(
        userId,
        Some(OffsetDateTime.now.plusYears(10).toEpochSecond)
      )(FakeRequest())
      status(groups) mustBe OK
      val retrieved = contentAsJson(groups).as[Map[FeatureName, GroupName]]
      retrieved.toList.size mustBe 1

      retrieved.contains(test1.feature) mustBe true
    }

    "get no groups if there are no ongoing tests at the time" in {

      val ab = fakeAb.copy(end = Some(OffsetDateTime.now.plusDays(2)))
      val creation = create(ab)
      status(creation) mustBe OK

      val userId: UserId = randomUserId

      val groups = controller.getGroups(
        userId,
        Some(OffsetDateTime.now.plusDays(3).toEpochSecond)
      )(FakeRequest())

      status(groups) mustBe OK
      val retrieved = contentAsJson(groups).as[Map[FeatureName, GroupName]]
      retrieved mustBe empty
    }

    "get no groups if the user does not have the tag required by the test" in {
      val ab = fakeAb.copy(requiredTags = List("English Speaking"))
      createAbtestOnServer(ab)

      val userId: UserId = randomUserId

      val groups =
        controller.getGroups(userId, tomorrow, Some(List("iPad")))(FakeRequest())

      status(groups) mustBe OK
      val retrieved = contentAsJson(groups).as[Map[FeatureName, GroupName]]
      retrieved mustBe empty
    }

    "get groups if the user does have the tag required by the test" in {
      val ab = fakeAb.copy(requiredTags = List("English Speaking"))
      createAbtestOnServer(ab)

      val userId: UserId = randomUserId

      val groups =
        controller.getGroups(userId, tomorrow, Some(List("English Speaking")))(
          FakeRequest()
        )

      status(groups) mustBe OK
      val retrieved = contentAsJson(groups).as[Map[FeatureName, GroupName]]
      retrieved mustNot be(empty)
    }

    "get no groups if the user does not have the tag required by the test - multiple tags" in {
      val ab = fakeAb.copy(requiredTags = List("English Speaking", "Feature N"))
      createAbtestOnServer(ab)

      val userId: UserId = randomUserId

      val groups =
        controller.getGroups(userId, tomorrow, Some(List("iPad")))(FakeRequest())

      status(groups) mustBe OK
      val retrieved = contentAsJson(groups).as[Map[FeatureName, GroupName]]
      retrieved mustBe empty
    }

    "get groups if the user does have the tag required by the test - multiple tags" in {
      val ab = fakeAb.copy(requiredTags = List("English Speaking", "Feature N"))
      createAbtestOnServer(ab)

      val userId: UserId = randomUserId

      val groups =
        controller.getGroups(
          userId,
          tomorrow,
          Some(List("English Speaking", "Feature N"))
        )(FakeRequest())

      status(groups) mustBe OK
      val retrieved = contentAsJson(groups).as[Map[FeatureName, GroupName]]
      retrieved mustNot be(empty)
    }

    "get groups if the user does have the tag required by the test - multiple tags separated with comma" in {
      val ab = fakeAb.copy(requiredTags = List("English Speaking", "Feature N"))
      createAbtestOnServer(ab)

      val userId: UserId = randomUserId

      val groups =
        controller.getGroups(
          userId,
          tomorrow,
          Some(List("English Speaking, Feature N"))
        )(FakeRequest())

      status(groups) mustBe OK
      val retrieved = contentAsJson(groups).as[Map[FeatureName, GroupName]]
      retrieved mustNot be(empty)
    }

  }

  "delete test" should {
    "terminate a test before expires" in {

      val test = createAbtestOnServer()

      val userId: UserId = randomUserId

      val asOf = Some(OffsetDateTime.now.plusHours(1).toEpochSecond)
      val groups = controller.getGroups(userId, asOf)(FakeRequest())

      val retrieved = contentAsJson(groups).as[Map[FeatureName, GroupName]]
      retrieved.toList.size mustBe 1

      contentAsJson(controller.terminate(test._id)(FakeRequest()))

      val groupsAfterTermination = controller.getGroups(userId, asOf)(FakeRequest())

      val retrievedAfterTermination =
        contentAsJson(groupsAfterTermination).as[Map[FeatureName, GroupName]]

      retrievedAfterTermination.toList mustBe empty

      val result = controller.get(test._id)(FakeRequest())

      contentAsJson(result)
        .as[Entity[Abtest]]
        .data
        .end
        .get
        .isBefore(Instant.now.plusSeconds(1)) mustBe true
    }

    "delete a test if it has not started yet" in {

      val test = createAbtestOnServer(fakeAb(1, 100))

      val userId: UserId = randomUserId

      val asOf = Some(OffsetDateTime.now.plusHours(1).toEpochSecond)

      contentAsJson(controller.terminate(test._id)(FakeRequest()))

      val groups = controller.getGroups(userId, asOf)(FakeRequest())

      val retrieved = contentAsJson(groups).as[Map[FeatureName, GroupName]]
      retrieved.toList.size mustBe 0

      val result = controller.get(test._id)(FakeRequest())

      status(result) must be(NOT_FOUND)

    }

    "do nothing if a test already expired" in {

      val test = createAbtestOnServer(
        fakeAb.copy(end = Some(OffsetDateTime.now.plusNanos(30000000)))
      )

      Thread.sleep(50) //wait until it expires.

      contentAsJson(controller.terminate(test._id)(FakeRequest()))

      val result = controller.get(test._id)(FakeRequest())

      contentAsJson(result).as[Entity[Abtest]] mustBe (test)

    }

  }

  "AlternativeId Integration" should {
    val n = 20
    val testUsingAlternativeId = fakeAb(
      groups = (1 to n).toList.map(i => Group(s"Group$i", 1d / n.toDouble)),
      alternativeIdName = Some("deviceId")
    )
    val userIds = (1 to 20).map(_ => randomUserId)

    "use alternative id if AlternativeIdName is set and alternative Id is present in user meta" in {
      createAbtestOnServer(testUsingAlternativeId)

      val assignments = userIds.map { userId =>
        val result = contentAsJson(
          controller.getGroupsWithMeta(
            jsonRequest(
              UserGroupQuery(
                Some(userId),
                at = Some(testUsingAlternativeId.start),
                meta = Map(
                  "deviceId" -> "123"
                )
              )
            )
          )
        ).as[UserGroupQueryResult]

        result.groups(testUsingAlternativeId.feature)
      }.distinct

      assignments.size mustBe 1 //since they have the same device id they should be assigned with the same group even though they have difference userIds

    }

    "do not participate if AlternativeIdName is set but alternative Id is not present in user meta" in {
      createAbtestOnServer(testUsingAlternativeId)

      val result = contentAsJson(
        controller.getGroupsWithMeta(
          jsonRequest(
            UserGroupQuery(
              Some(randomUserId),
              at = Some(testUsingAlternativeId.start),
              meta = Map()
            )
          )
        )
      ).as[UserGroupQueryResult]
      result.groups.contains(testUsingAlternativeId.feature) mustBe false
    }

    "works with overrides" in {
      val ab = createAbtestOnServer(
        testUsingAlternativeId.copy(groups = List(Group("A", 1), Group("B", 0)))
      )
      val deviceId = randomUserId
      toServer(controller.addOverride(ab.data.feature, deviceId, "B"))

      val result = getGroups(
        None,
        at = Some(testUsingAlternativeId.start),
        meta = Map("deviceId" -> deviceId)
      )

      result(testUsingAlternativeId.feature) mustBe "B"

    }

  }

  "Matching Meta integration" should {
    "not eligible to test if no matching meta" in {
      val ab = createAbtestOnServer(
        fakeAb(userMetaCriteria = Some(and(ExactMatch("sex", "M"))))
      )
      getGroups(Some(randomUserId), Some(ab.data.start), Map()) must be(empty)
      getGroups(Some(randomUserId), Some(ab.data.start), Map("sex" -> "F")) must be(
        empty
      )
    }

    "eligible to test if there is matching meta" in {
      val ab = createAbtestOnServer(
        fakeAb(userMetaCriteria = Some(and(ExactMatch("sex", "M"))))
      )
      getGroups(Some(randomUserId), Some(ab.data.start), Map("sex" -> "M")).size mustBe 1
    }

    "eligible to test if there is matching meta with regex" in {
      val ab =
        createAbtestOnServer(
          fakeAb(userMetaCriteria = Some(and(RegexMatch("sex", "Male|^M$"))))
        )
      getGroups(Some(randomUserId), Some(ab.data.start), Map("sex" -> "Male")).size mustBe 1
      getGroups(Some(randomUserId), Some(ab.data.start), Map("sex" -> "M")).size mustBe 1
    }

    "Not eligible to test if there is one mismatch meta" in {
      val ab = createAbtestOnServer(
        fakeAb(
          userMetaCriteria =
            Some(and(RegexMatch("sex", "Male|^M$"), RegexMatch("age", "^2\\d$")))
        )
      )
      getGroups(
        Some(randomUserId),
        Some(ab.data.start),
        Map("sex" -> "Male", "age" -> "33")
      ) must be(empty)
    }

    "Eligible to test all criterion are met" in {
      val ab = createAbtestOnServer(
        fakeAb(
          userMetaCriteria =
            Some(and(RegexMatch("sex", "Male|^M$"), RegexMatch("age", "^2\\d$")))
        )
      )
      getGroups(
        Some(randomUserId),
        Some(ab.data.start),
        Map("sex" -> "Male", "age" -> "23", "occupation" -> "engineer")
      ).size mustBe 1
    }
  }

  "Segment Range integration" should {
    "two tests sharing the same range should have the same group of users" in {
      createAbtestOnServer(fakeAb(segRanges = List(GroupRange(0, 0.3))))
      val ab = createAbtestOnServer(fakeAb(segRanges = List(GroupRange(0, 0.3))))

      val userAssignments = (1 to 500).map { _ =>
        val assignment = getGroups(Some(randomUserId), at = Some(ab.data.start))
        (assignment.size == 0 || assignment.size == 2) mustBe true
        assignment
      }

      val countOfUsersInTests = userAssignments.count(_.size == 2)
      countOfUsersInTests.toDouble / 500d mustBe (0.3 +- 0.1)
    }

    "two tests having mutually exclusive ranges should have no overlaps of users" in {
      createAbtestOnServer(fakeAb(segRanges = List(GroupRange(0, 0.3))))
      val ab =
        createAbtestOnServer(fakeAb(segRanges = List(GroupRange(0.30001, 0.5))))

      val userAssignments = (1 to 500).map { _ =>
        val userId = randomUserId
        val assignment = getGroups(Some(userId), at = Some(ab.data.start))
        (assignment.size == 0 || assignment.size == 1) mustBe true
        assignment
      }

      val countOfUsersInAb =
        userAssignments.count(_.headOption.fold(false)(_._1 == ab.data.feature))
      countOfUsersInAb.toDouble / 500d mustBe (0.2 +- 0.05)
    }

  }

  "reshuffle Integration" should {
    "reassign majority of the users" in {
      val originalSpec = fakeAb
      createAbtestOnServer(originalSpec)

      val userAssignments = (1 to 200).map(_ => randomUserId).map { userId =>
        userId -> getGroups(userId = Some(userId), at = Some(originalSpec.start))(
          originalSpec.feature
        )
      }

      val reshuffled = createAbtestOnServer(
        Some(
          originalSpec.copy(start = originalSpec.start.plusDays(1), reshuffle = true)
        ),
        auto = true
      )

      val changedRatio = userAssignments.count {
        case (userId, group) =>
          getGroups(userId = Some(userId), at = Some(reshuffled.data.start))(
            originalSpec.feature
          ) != group
      }.toDouble / userAssignments.size.toDouble

      changedRatio mustBe >(0.3d)

    }

    "do not reassign already reshuffled tests" in {
      val originalSpec = fakeAb()
      createAbtestOnServer(originalSpec)
      val reshuffled = createAbtestOnServer(
        Some(
          originalSpec.copy(start = originalSpec.start.plusDays(1), reshuffle = true)
        ),
        auto = true
      )

      val userAssignments = (1 to 200).map(_ => randomUserId).map { userId =>
        userId -> getGroups(userId = Some(userId), at = Some(reshuffled.data.start))(
          originalSpec.feature
        )
      }

      val continued = createAbtestOnServer(
        Some(originalSpec.copy(start = originalSpec.start.plusDays(2))),
        auto = true
      )

      userAssignments.forall {
        case (userId, group) =>
          getGroups(userId = Some(userId), at = Some(continued.data.start))(
            originalSpec.feature
          ) == group
      } mustBe true

    }

  }

  "group meta integration" should {
    "return meta together with user" in {

      val ab = createAbtestOnServer(fakeAb(1, 20))

      val feature = ab.data.feature

      toServer(
        controller.addGroupMetas(ab._id, false),
        jsonRequest(
          Json.obj("A" -> Json.obj("ff" -> "a"), "B" -> Json.obj("ff" -> "b"))
        )
      )

      val userIds = (1 to 20).map(_ => randomUserId)

      userIds.foreach { userId =>
        val result = contentAsJson(
          controller.getGroupsWithMeta(
            jsonRequest(
              UserGroupQuery(Some(userId), at = Some(ab.data.start))
            )
          )
        ).as[UserGroupQueryResult]
        result.groups.keys must contain(feature)
        result.metas.keys must contain(feature)
        result.metas(feature).value("ff").as[String] mustBe result
          .groups(feature)
          .toLowerCase
      }

    }

    "Cannot change meta for test already started when auto is false" in {
      val ab = createAbtestOnServer(fakeAb.copy(start = OffsetDateTime.now))
      Thread.sleep(100)
      val r = controller.addGroupMetas(ab._id, false)(
        jsonRequest(Json.obj("A" -> Json.obj("ff" -> "a")))
      )
      status(r) mustBe BAD_REQUEST
    }

    "Can change meta for test already started when auto is true" in {
      val ab = createAbtestOnServer(fakeAb.copy(start = OffsetDateTime.now))
      val feature = ab.data.feature

      Thread.sleep(100)
      toServer(
        controller.addGroupMetas(ab._id, true),
        jsonRequest(
          Json.obj("A" -> Json.obj("ff" -> "a"), "B" -> Json.obj("ff" -> "b"))
        )
      )

      val resultTests =
        contentAsJson(controller.getByFeature(feature)(FakeRequest()))
          .as[List[Entity[Abtest]]]

      resultTests.size mustBe 2

      val testResult = contentAsJson(
        controller.get(resultTests.head._id.value)(FakeRequest())
      ).as[Entity[Abtest]]
      testResult.data.groupMetas mustBe Map(
        "A" -> Json.obj("ff" -> "a"),
        "B" -> Json.obj("ff" -> "b")
      )
    }

    "throw validation error when group name in meta does not exist in test" in {
      val ab = createAbtestOnServer(fakeAb(1))
      val r = controller.addGroupMetas(ab._id, false)(
        jsonRequest(Json.obj("NonexistingGroup" -> Json.obj("ff" -> "a")))
      )
      status(r) mustBe BAD_REQUEST
    }

    "pass validation when not all the groups in tests are mentioned in meta" in {
      val ab = createAbtestOnServer(fakeAb(1))
      val r = controller.addGroupMetas(ab._id, false)(
        jsonRequest(Json.obj("A" -> Json.obj("ff" -> "a")))
      )
      status(r) mustBe OK
    }

    "does not return meta that doesn't come with one" in {
      val ab = createAbtestOnServer(fakeAb.copy(groups = List(Group("A", 1))))
      val result = contentAsJson(
        controller.getGroupsWithMeta(
          jsonRequest(
            UserGroupQuery(Some(randomUserId), at = Some(ab.data.start))
          )
        )
      ).as[UserGroupQueryResult]

      result.metas.contains(ab.data.feature) mustBe false
    }

    "subsequent tests inherits meta settings" in {
      val ab = createAbtestOnServer(fakeAb(1, 20))

      toServer(
        controller.addGroupMetas(ab._id, false),
        jsonRequest(
          Json.obj("A" -> Json.obj("ff" -> "a"), "B" -> Json.obj("ff" -> "b"))
        )
      )

      val subSequent =
        createAbtestOnServer(fakeAb(21, 25, feature = ab.data.feature))

      val result = contentAsJson(
        controller.getGroupsWithMeta(
          jsonRequest(
            UserGroupQuery(Some(randomUserId), at = Some(subSequent.data.start))
          )
        )
      ).as[UserGroupQueryResult]

      result.metas.contains(ab.data.feature) mustBe true
    }

    "subsequent test from spec inherits meta settings when new spec has empty meta" in {

      val metas = Map("A" -> Json.obj("ff" -> "a"), "B" -> Json.obj("ff" -> "b"))
      val ab = createAbtestOnServer(fakeAb(1, 20, groupMetas = metas))

      val subSequent =
        createAbtestOnServer(fakeAb(21, 25, feature = ab.data.feature))

      subSequent.data.groupMetas mustBe metas
    }

    "subsequent test from spec rewrite meta settings " in {

      val metas = Map("A" -> Json.obj("ff" -> "a"), "B" -> Json.obj("ff" -> "b"))
      val ab = createAbtestOnServer(fakeAb(1, 20, groupMetas = metas))

      val newMetas =
        Map("A" -> Json.obj("ff" -> "aa"), "B" -> Json.obj("ff" -> "bb"))

      val subSequent = createAbtestOnServer(
        fakeAb(21, 25, feature = ab.data.feature, groupMetas = newMetas)
      )

      subSequent.data.groupMetas mustBe newMetas
    }
  }

  "PUT /tests/overrides" should {
    "add an override to an existing test" in {

      val ab = createAbtestOnServer()

      val userIds = (1 to 20).map(_ => randomUserId)

      val overrideGroup = ab.data.groups.last.name

      userIds.foreach { userId =>
        val addResult =
          controller.addOverride(ab.data.feature, userId, overrideGroup)(
            FakeRequest()
          )
        status(addResult) mustBe OK
      }

      userIds.foreach { userId =>
        val groups = controller.getGroups(userId, tomorrow)(FakeRequest())
        val retrieved = contentAsJson(groups).as[Map[FeatureName, GroupName]]
        retrieved(ab.data.feature) mustBe overrideGroup
      }
    }

    "override honor eligibility control" in {

      val ab = createAbtestOnServer(
        fakeAb(groups = List(Group("A", 0)), requiredTags = List("tag1"))
      )

      val userId = "1"
      toServer(controller.addOverride(ab.data.feature, userId, "A"))

      val groups = controller.getGroups(userId, tomorrow)(FakeRequest())
      val retrieved = contentAsJson(groups).as[Map[FeatureName, GroupName]]
      retrieved.get(ab.data.feature) mustBe empty
    }

    "override overrides eligibility control when set so" in {

      val ab = createAbtestOnServer(
        fakeAb(groups = List(Group("A", 0)), requiredTags = List("tag1"))
      )

      val userId = "1"
      toServer(controller.addOverride(ab.data.feature, userId, "A"))
      toServer(controller.setOverrideEligibilityIn(ab.data.feature, true))

      val groups = controller.getGroups(userId, tomorrow)(FakeRequest())
      val retrieved = contentAsJson(groups).as[Map[FeatureName, GroupName]]
      retrieved.get(ab.data.feature) mustBe Some("A")
    }

    "retrieve meta according to overrides" in {

      val overrideGroup = "B"

      val ab = createAbtestOnServer(
        fakeAb(1, 20).copy(groups = List(Group("A", 1), Group(overrideGroup, 0)))
      )

      toServer(
        controller.addGroupMetas(ab._id, false),
        jsonRequest(
          Json.obj(
            "A" -> Json.obj("ff" -> "a"),
            overrideGroup -> Json.obj("ff" -> "b")
          )
        )
      )

      val userId = randomUserId

      toServer(controller.addOverride(ab.data.feature, userId, overrideGroup))

      val result = contentAsJson(
        controller.getGroupsWithMeta(
          jsonRequest(
            UserGroupQuery(Some(userId), at = Some(ab.data.start))
          )
        )
      ).as[UserGroupQueryResult]

      result.metas(ab.data.feature).value("ff").as[String] mustBe "b"
    }

  }

  "DELETE /features/:feature/overrides/:userId" should {
    "remove an override to an existing test" in {

      val ab =
        createAbtestOnServer(fakeAb(1, 2, feature = "a_new_feature_to_override"))

      val userId1 = randomUserId
      val userId2 = randomUserId

      val overrideGroup = ab.data.groups.last.name

      toServer(controller.addOverride(ab.data.feature, userId1, overrideGroup))

      toServer(controller.addOverride(ab.data.feature, userId2, overrideGroup))

      toServer(controller.removeOverride(ab.data.feature, userId2))

      val retrievedOverridesAfterRemoval = contentAsJson(
        toServer(controller.getOverrides(ab.data.feature))
      ).as[Feature].overrides

      retrievedOverridesAfterRemoval mustBe Map(userId1 -> overrideGroup)
    }
  }

  "DELETE /features/:feature/overrides" should {
    "remove an override to an existing test" in {

      val ab =
        createAbtestOnServer(fakeAb(1, 2, feature = "a_new_feature_to_override"))

      val userId1 = randomUserId
      val userId2 = randomUserId

      val overrideGroup = ab.data.groups.last.name

      toServer(controller.addOverride(ab.data.feature, userId1, overrideGroup))

      toServer(controller.addOverride(ab.data.feature, userId2, overrideGroup))

      toServer(controller.removeAllOverrides(ab.data.feature))

      val retrievedOverridesAfterRemoval = contentAsJson(
        toServer(controller.getOverrides(ab.data.feature))
      ).as[Feature].overrides

      retrievedOverridesAfterRemoval mustBe empty
    }
  }

  "Continuation integration test" should {

    def getGroupAssignment(
        test: Entity[Abtest],
        ids: List[UserId]
      ): Map[GroupName, List[UserId]] =
      ids
        .flatMap { uid =>
          val response = controller.getGroupsWithMeta(
            jsonRequest(
              UserGroupQuery(
                Some(uid.toString),
                at = Some(test.data.start.plusSeconds(1))
              )
            )
          )
          val result = contentAsJson(response).as[UserGroupQueryResult]
          result.groups.get(test.data.feature).map((_, uid))
        }
        .groupBy(_._1)
        .mapValues(_.map(_._2))

    "Inherits as many users from previous test as possible" in {
      val ab1 = createAbtestOnServer(
        fakeAb(1, 2, "a_feature")
          .copy(groups = List(Group("A", 0.3), Group("B", 0.3), Group("C", 0.2)))
      )
      val ab2 = createAbtestOnServer(
        fakeAb(3, 4, "a_feature")
          .copy(groups = List(Group("D", 0.2), Group("A", 0.5), Group("B", 0.2)))
      )
      val ab3 = createAbtestOnServer(
        fakeAb(5, 6, "a_feature")
          .copy(groups = List(Group("B", 0.1), Group("A", 0.6), Group("C", 0.2)))
      )

      val ids = (0 to 1000).toList.map(_.toString)
      val groupAssignment1 = getGroupAssignment(ab1, ids)
      val groupAssignment2 = getGroupAssignment(ab2, ids)
      val groupAssignment3 = getGroupAssignment(ab3, ids)

      //expanding group should see all users from the same group in the previous test
      groupAssignment2("A") must contain allElementsOf groupAssignment1("A")
      groupAssignment3("A") must contain allElementsOf groupAssignment2("A")

      //shrinking group should inherit all users from the same group in the previous test
      groupAssignment1("B") must contain allElementsOf groupAssignment2("B")
      groupAssignment2("B") must contain allElementsOf groupAssignment3("B")
    }

    "grouping should be deterministic regardless of time or machine" in {
      val ab1 = createAbtestOnServer(
        fakeAb(
          1,
          2,
          "a_feature",
          groups = List(Group("A", 0.3), Group("B", 0.3), Group("C", 0.2))
        )
      )
      val ab2 = createAbtestOnServer(
        fakeAb(
          3,
          4,
          "a_feature",
          groups = List(Group("D", 0.2), Group("A", 0.5), Group("B", 0.2))
        )
      )
      val ab3 = createAbtestOnServer(
        fakeAb(
          5,
          6,
          "a_feature",
          groups = List(Group("B", 0.1), Group("A", 0.6), Group("C", 0.2))
        )
      )

      val ids = (253 until 319).toList.map(_.toString)

      val groupAssignment1 = getGroupAssignment(ab1, ids)
      val groupAssignment2 = getGroupAssignment(ab2, ids)
      val groupAssignment3 = getGroupAssignment(ab3, ids)

      //hard coded to make sure that these values do not change for to maintain compatibility.
      groupAssignment1("A") must be(
        List(314, 310, 303, 299, 292, 288, 286, 285, 278, 273, 267, 265, 261, 257,
          256, 254).sorted.map(_.toString)
      )
      groupAssignment1("B") must be(
        List(317, 312, 311, 306, 302, 298, 294, 291, 279, 277, 275, 274, 271, 268,
          266, 262, 260).sorted.map(_.toString)
      )
      groupAssignment2("A") must be(
        List(316, 315, 314, 313, 310, 307, 304, 303, 299, 297, 296, 293, 292, 289,
          288, 286, 285, 284, 283, 282, 281, 278, 276, 273, 267, 265, 264, 261, 259,
          257, 256, 254).sorted.map(_.toString)
      )
      groupAssignment2("B") must be(
        List(317, 312, 306, 298, 294, 291, 277, 271, 268, 262,
          260).sorted.map(_.toString)
      )
      groupAssignment3("A") must be(
        List(316, 315, 314, 313, 310, 309, 308, 307, 305, 304, 303, 301, 299, 297,
          296, 295, 293, 292, 290, 289, 288, 286, 285, 284, 283, 282, 281, 278, 276,
          273, 270, 267, 265, 264, 261, 259, 258, 257, 256, 255, 254,
          253).sorted.map(_.toString)
      )
      groupAssignment3("B") must be(List(312, 298, 277, 268).sorted.map(_.toString))

    }

    "regression range evolution" in {
      val originalSpec =
        fakeAb(start = 1, groups = List(Group("A", 0), Group("B", 0)))
      createAbtestOnServer(originalSpec)

      val continued = createAbtestOnServer(
        Some(
          fakeAb(
            feature = originalSpec.feature,
            start = 2,
            groups = List(Group("A", 0.1), Group("B", 0.1))
          )
        ),
        auto = true
      )

      continued.data.ranges mustBe Map(
        "A" -> List(GroupRange(0, 0.1)),
        "B" -> List(GroupRange(0.1, 0.2))
      )
    }

    "zero sized group has no range" in {
      val spec = fakeAb(start = 1, groups = List(Group("A", 0), Group("B", 0)))
      val created = createAbtestOnServer(spec)
      created.data.ranges.values.map { range =>
        range must be(empty)
      }

      contentAsJson(
        toServer(
          controller.getGroups("1234", Some(spec.start.plusMinutes(1).toEpochSecond))
        )
      ).as[Map[String, String]] must be(empty)
    }
  }
}

class AssessmentAlgIntegrationSuite extends AbtestIntegrationSuiteBase {
  "Get KPI" should {
    "return 404 when there is no distribution" in {
      val r = controller.getKPIDistribution("non-exist")(FakeRequest())
      status(r) mustBe NOT_FOUND
    }

    "create one when there isn't one already" in {
      val kpi: KPIDistribution =
        GammaKPIDistribution(KPIName("new KPI"), Normal(1d, 0.1d), Normal(3d, 0.3d))
      val r = controller.updateKPIDistribution(jsonRequest(kpi))
      status(r) mustBe OK
      contentAsJson(r).as[KPIDistribution] mustBe kpi
    }

    "update one" in {
      val gkpi =
        GammaKPIDistribution(
          KPIName("another KPI"),
          Normal(1d, 0.1d),
          Normal(3d, 0.3d)
        )
      val kpi: KPIDistribution = gkpi
      val kpiUpdated: KPIDistribution = gkpi.copy(shapePrior = Normal(1.3, 0.13d))

      toServer(controller.updateKPIDistribution, jsonRequest(kpi))

      toServer(controller.updateKPIDistribution, jsonRequest(kpiUpdated))

      val r = controller.getKPIDistribution("another KPI")(FakeRequest())
      contentAsJson(r).as[KPIDistribution] mustBe kpiUpdated

    }
  }
}

class AbtestIntegrationSuiteBase
    extends PlaySpec
    with GuiceOneAppPerSuite
    with BeforeAndAfter {

  implicit def toEntityId(sid: String): EntityId = EntityId(sid)
  implicit def toString(eid: EntityId): String = eid.value

  type F[A] = IO[A]

  import _root_.play.api.inject.ApplicationLifecycle
  import _root_.play.api.Configuration

  lazy val al = app.injector.instanceOf[ApplicationLifecycle]
  implicit val ec = app.injector.instanceOf[concurrent.ExecutionContext]

  lazy val provider =
    new AbtestAPIProvider(new Configuration(ConfigFactory.load()), al)
  lazy val api = provider.api

  lazy val controller = new AbtestController(
    api,
    provider.kpiApi,
    app.injector.instanceOf[ControllerComponents],
    None
  )

  def fakeAb: AbtestSpec = fakeAb()

  def fakeAb(
      start: Int = 0,
      end: Int = 100,
      feature: String = "AMakeUpFeature" + Random.alphanumeric.take(5).mkString,
      alternativeIdName: Option[MetaFieldName] = None,
      groups: List[Group] = List(Group("A", 0.5), Group("B", 0.5)),
      userMetaCriteria: UserMetaCriteria = None,
      segRanges: List[GroupRange] = Nil,
      requiredTags: List[Tag] = Nil,
      groupMetas: GroupMetas = Map()
    ): AbtestSpec = AbtestSpec(
    name = "test",
    author = "kai",
    feature = feature,
    start = OffsetDateTime.now.plusDays(start.toLong),
    end = Some(OffsetDateTime.now.plusDays(end.toLong)),
    groups = groups,
    alternativeIdName = alternativeIdName,
    userMetaCriteria = userMetaCriteria,
    segmentRanges = segRanges,
    requiredTags = requiredTags,
    groupMetas = groupMetas
  )

  after {
    import cats.implicits._
    List[EntityDAO[F, _, JsObject]](
      provider.daos._1,
      provider.daos._2,
      provider.daos._3
    ).traverse(_.removeAll(Json.obj()))
      .handleErrorWith { e =>
        IO(println("Failed to clean up DB after: " + e.getMessage))
      }
      .unsafeRunSync()

  }

  def randomUserId = Random.alphanumeric.take(10).mkString

  lazy val tomorrow = Some(OffsetDateTime.now.plusDays(1).toEpochSecond)

  def create(
      t: AbtestSpec,
      auto: Boolean = false
    ) =
    controller.create(auto)(jsonRequest(t))

  def jsonRequest[T: Writes](t: T) = FakeRequest().withBody(Json.toJson(t))

  def createAbtestOnServer(): Entity[Abtest] = createAbtestOnServer(None)
  def createAbtestOnServer(test: AbtestSpec): Entity[Abtest] =
    createAbtestOnServer(Some(test))
  def createAbtestOnServer(
      test: Option[AbtestSpec],
      auto: Boolean = false
    ): Entity[Abtest] = {
    val ab = test.getOrElse(fakeAb)
    val creation = create(ab, auto)
    if (status(creation) != OK)
      println(contentAsString(creation))
    status(creation) mustBe OK
    val result = contentAsJson(creation).as[Entity[Abtest]]
    result
  }

  def toServer[T](
      action: Action[T],
      request: Request[T] = FakeRequest()
    ): Future[Result] = {
    val r = action.apply(request)
    val s = status(r)
    if (s != OK)
      println(contentAsString(r))
    s mustBe OK
    r
  }

  def getTestFromServer(id: EntityId): Entity[Abtest] =
    contentAsJson(controller.get(id)(FakeRequest())).as[Entity[Abtest]]

  def getGroups(
      userId: Option[UserId] = None,
      at: Option[OffsetDateTime] = Some(OffsetDateTime.now.plusDays(1)),
      meta: Map[String, String] = Map()
    ): Map[FeatureName, GroupName] = {
    contentAsJson(
      controller.getGroupsWithMeta(
        jsonRequest(
          UserGroupQuery(
            userId,
            at = at,
            meta = meta
          )
        )
      )
    ).as[UserGroupQueryResult].groups
  }

  implicit def fromInstantToOffset(instant: Instant): OffsetDateTime =
    instant.atOffset(ZoneOffset.UTC)

  implicit def fromInstantOToOffset(
      instant: Option[Instant]
    ): Option[OffsetDateTime] =
    instant.map(fromInstantToOffset)

  implicit def fromOffsetDateTimeToInstant(offsetDateTime: OffsetDateTime): Instant =
    offsetDateTime.toInstant

  implicit def fromOffsetDateTimeOToInstant(
      offsetDateTime: Option[OffsetDateTime]
    ): Option[Instant] =
    offsetDateTime.map(fromOffsetDateTimeToInstant)
}
