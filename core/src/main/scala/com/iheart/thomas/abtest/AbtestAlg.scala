/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package abtest

import java.time.{Instant, OffsetDateTime, ZoneOffset}

import _root_.play.api.libs.json._
import cats._
import cats.implicits._
import cats.tagless.FunctorK
import com.iheart.thomas.TimeUtil
import Error._
import cats.effect.{Concurrent, Resource, Timer}
import com.iheart.thomas.abtest.AssignGroups.{AssignmentResult, AssignmentWithMeta}
import model.Abtest.{Specialization, Status}
import model._
import lihua._
import monocle.macros.syntax.lens._
import mouse.all._
import henkan.convert.Syntax._
import mau.RefreshRef
import play.api.libs.json.Json.JsValueWrapper

import scala.concurrent.duration._
import scala.util.Random

/**
  * Algebra for ABTest API
  * Final Tagless encoding
  * @tparam F
  */
trait AbtestAlg[F[_]] extends DataProvider[F] {

  def create(
      testSpec: AbtestSpec,
      auto: Boolean
    ): F[Entity[Abtest]]

  /**
    * Stop a test before it ends
    * @param test
    * @return Some(test) if it already started, None if not started yet.
    */
  def terminate(test: TestId): F[Option[Entity[Abtest]]]

  def getTest(test: TestId): F[Entity[Abtest]]

  def updateTest(
      testId: TestId,
      spec: AbtestSpec
    ): F[Entity[Abtest]]

  /**
    *
    * @param feature
    * @return tests for this feature in chronological descending order
    */
  def getTestsByFeature(feature: FeatureName): F[Vector[Entity[Abtest]]]

  def continue(spec: AbtestSpec): F[Entity[Abtest]]

  def getAllFeatures: F[List[FeatureName]]

  /**
    * Get all the tests
    * @param time optional time constraint, if set, this will only return tests as of that time.
    */
  def getAllTests(time: Option[OffsetDateTime]): F[Vector[Entity[Abtest]]]

  def getAllTestsBySpecialization(
      specialization: Specialization,
      time: Option[OffsetDateTime]
    ): F[Vector[Entity[Abtest]]]

  def getAllTestsEpoch(time: Option[Long]): F[Vector[Entity[Abtest]]] =
    getAllTests(time.map(TimeUtil.toDateTime))

  def setOverrideEligibilityIn(
      feature: FeatureName,
      overrideEligibility: Boolean
    ): F[Feature]

  /**
    * Get all the tests that end after a certain time
    * @param time optional time constraint, if set, this will only return tests as of that time.
    */
  def getAllTestsEndAfter(time: OffsetDateTime): F[Vector[Entity[Abtest]]]

  def getAllTestsEndAfter(time: Long): F[Vector[Entity[Abtest]]] =
    getAllTestsEndAfter(TimeUtil.toDateTime(time))

  /**
    * Get all the tests together with their Feature cached.
    * @param time optional time constraint, if set, this will only return tests as of that time.
    */
  def getAllTestsCached(
      time: Option[OffsetDateTime]
    ): F[Vector[(Entity[Abtest], Feature)]]

  def getAllTestsCachedEpoch(
      time: Option[Long]
    ): F[Vector[(Entity[Abtest], Feature)]] =
    getAllTestsCached(time.map(TimeUtil.toDateTime))

  def addOverrides(
      featureName: FeatureName,
      overrides: Overrides
    ): F[Feature]

  def getOverrides(featureName: FeatureName): F[Feature] =
    getFeature(featureName)

  def getFeature(featureName: FeatureName): F[Feature]

  def updateFeature(feature: Feature): F[Feature]

  def removeOverrides(
      featureName: FeatureName,
      userId: UserId
    ): F[Feature]

  def removeAllOverrides(featureName: FeatureName): F[Feature]

  /**
    * get all groups of a user by features
    * @param time
    */
  def getGroups(
      userId: UserId,
      time: Option[OffsetDateTime],
      tags: List[Tag]
    ): F[Map[FeatureName, GroupName]]

  def cleanUp(
      featureName: FeatureName,
      historyBefore: OffsetDateTime
    ): F[Int]

  def getGroupsWithMeta(query: UserGroupQuery): F[UserGroupQueryResult]

  def addGroupMetas(
      test: TestId,
      metas: Map[GroupName, GroupMeta],
      auto: Boolean
    ): F[Entity[Abtest]]

  def updateUserMetaCriteria(
      testId: TestId,
      userMetaCriteria: UserMetaCriteria,
      auto: Boolean
    ): F[Entity[Abtest]]

  def removeGroupMetas(
      test: TestId,
      auto: Boolean
    ): F[Entity[Abtest]]

  /**
    * Get the assignments for a list of ids bypassing the eligibility control
    */
  def getGroupAssignments(
      ids: List[String],
      feature: FeatureName,
      at: OffsetDateTime
    ): F[List[(String, GroupName)]]
}

object AbtestAlg {
  implicit val functorKInstance: FunctorK[AbtestAlg] =
    cats.tagless.Derive.functorK[AbtestAlg]

  def defaultResource[F[_]: Timer](
      refreshPeriod: FiniteDuration
    )(implicit
      abTestDao: EntityDAO[F, Abtest, JsObject],
      featureDao: EntityDAO[F, Feature, JsObject],
      F: Concurrent[F],
      eligibilityControl: EligibilityControl[F],
      idSelector: EntityId => JsObject
    ): Resource[F, AbtestAlg[F]] =
    RefreshRef
      .resource[F, TestsData]
      .map { implicit rr =>
        implicit val nowF = F.delay(Instant.now)
        new DefaultAbtestAlg[F](refreshPeriod)
      }
}

final class DefaultAbtestAlg[F[_]](
    refreshPeriod: FiniteDuration,
    staleTimeout: FiniteDuration = 30.minutes
  )(implicit
    private[thomas] val abTestDao: EntityDAO[
      F,
      Abtest,
      JsObject
    ],
    private[thomas] val featureDao: EntityDAO[
      F,
      Feature,
      JsObject
    ],
    refreshRef: RefreshRef[F, TestsData],
    nowF: F[Instant],
    F: MonadThrowable[F],
    eligibilityControl: EligibilityControl[F],
    idSelector: EntityId => JsObject)
    extends AbtestAlg[F] {
  import QueryDSL._

  def create(
      testSpec: AbtestSpec,
      auto: Boolean
    ): F[Entity[Abtest]] =
    addTestWithLock(testSpec.feature)(
      createWithoutLock(testSpec, auto)
    )

  def updateTest(
      testId: TestId,
      spec: AbtestSpec
    ): F[Entity[Abtest]] =
    for {
      _ <- validateForCreation(spec)
      test <- getTest(testId)
      _ <- ensure(test.data.feature == spec.feature, FeatureCannotBeChanged)
      now <- nowF
      _ <- ensure(test.data.isScheduled(now), CannotChangePastTest(now))
      tests <- getTestsByFeature(spec.feature)
      (beforeO, afterO) = {
        val index = tests.indexWhere(_._id == test._id)
        (tests.get(index.toLong + 1L), tests.get(index.toLong - 1L))
      }
      _ <- beforeO.fold(F.unit)(
        before =>
          ensure(
            before.data.end.fold(false)(_.isBefore(spec.startI)),
            ConflictTest(before)
          )
      )
      _ <- afterO.fold(F.unit)(
        after =>
          ensure(
            spec.endI.fold(false)(_.isBefore(after.data.start)),
            ConflictTest(after)
          ) *>
            ensure(
              groupsEqualSizes(spec.groups, test.data.groups),
              CannotChangeGroupSizeWithFollowUpTest(after)
            )
      )
      r <- addTestWithLock(spec.feature)(
        abTestDao.update(test.copy(data = testFromSpec(spec, Some(test))))
      )

    } yield r

  def continue(testSpec: AbtestSpec): F[Entity[Abtest]] =
    addTestWithLock(testSpec.feature) {
      for {
        _ <- validateForCreation(testSpec)
        continueFrom <- getTestByFeature(testSpec.feature)
        created <- continueWith(testSpec, continueFrom)
      } yield created
    }

  def getAllTestsCachedWithAt(time: Option[Instant]): F[TestsData] =
    time.fold(
      refreshRef.getOrFetch(refreshPeriod, staleTimeout)(
        nowF.flatMap(getTestsData(_, None))
      ) {
        case e => F.unit //todo: add logging here for Abtest retrieval failure
      }
    )(getTestsData(_, None))

  def getAllTestsCached(
      time: Option[OffsetDateTime]
    ): F[Vector[(Entity[Abtest], Feature)]] =
    getAllTestsCachedWithAt(time.map(_.toInstant)).map(_.data)

  def getTestsData(
      at: Instant,
      duration: Option[FiniteDuration]
    ): F[TestsData] =
    for {
      tests <- abTestDao.find(abtests.byTime(at, duration))
      features <- featureDao.find(
        Json.obj(
          "name" ->
            Json.obj(
              "$in" ->
                JsArray(
                  tests.map(t => JsString(t.data.feature))
                )
            )
        )
      )
    } yield TestsData(
      at,
      tests.map(
        t =>
          (
            t,
            features
              .find(_.data.name == t.data.feature)
              .map(_.data)
              .getOrElse(
                Feature(t.data.feature, None, Map())
              )
          )
      ),
      duration
    )

  def getAllFeatures: F[List[FeatureName]] =
    abTestDao
      .find(Json.obj())
      .map(_.map(_.data.feature).distinct.toList.sorted)

  def getTest(id: TestId): F[Entity[Abtest]] =
    abTestDao.get(id)

  def getAllTests(time: Option[OffsetDateTime]): F[Vector[Entity[Abtest]]] =
    nowF.flatMap { n =>
      abTestDao.find(abtests.byTime(time.map(_.toInstant).getOrElse(n), None))
    }

  def getAllTestsEndAfter(time: OffsetDateTime): F[Vector[Entity[Abtest]]] =
    abTestDao.find(abtests.endTimeAfter(time.toInstant))

  def getTestsByFeature(feature: FeatureName): F[Vector[Entity[Abtest]]] =
    abTestDao
      .find(Json.obj("feature" -> JsString(feature)))
      .map(
        _.sortBy(
          t =>
            (
              t.data.start,
              t.data.end.getOrElse(Instant.MAX)
            )
        ).reverse
      )

  def getTestByFeature(feature: FeatureName): F[Entity[Abtest]] =
    getTestsByFeature(feature)
      .ensure(
        Error.NotFound(s"No tests found under $feature")
      )(_.nonEmpty)
      .map(_.head)

  def getTestByFeature(
      feature: FeatureName,
      at: OffsetDateTime
    ): F[Entity[Abtest]] =
    abTestDao.findOne(
      Json.obj("feature" -> JsString(feature)) ++ abtests
        .byTime(at)
    )

  def addOverrides(
      featureName: FeatureName,
      overrides: Overrides
    ): F[Feature] =
    for {
      feature <- ensureFeature(featureName)
      updated <- featureDao.update(
        feature
          .lens(_.data.overrides)
          .modify(_ ++ overrides)
      )
    } yield updated.data

  def updateFeature(feature: Feature): F[Feature] =
    for {
      fe <- featureDao.byName(feature.name)
      updated <- featureDao.update(
        fe.copy(data = feature.copy(lockedAt = fe.data.lockedAt))
      )
    } yield updated.data

  def setOverrideEligibilityIn(
      featureName: FeatureName,
      overrideEligibility: Boolean
    ): F[Feature] =
    for {
      feature <- ensureFeature(featureName)
      updated <- featureDao.update(
        feature
          .lens(_.data.overrideEligibility)
          .set(overrideEligibility)
      )
    } yield updated.data

  def removeOverrides(
      featureName: FeatureName,
      userId: UserId
    ): F[Feature] =
    for {
      feature <- featureDao.byName(featureName)
      updated <- featureDao.update(
        feature.lens(_.data.overrides).modify(_ - userId)
      )
    } yield updated.data

  def getAllTestsBySpecialization(
      specialization: Specialization,
      time: Option[OffsetDateTime]
    ): F[Vector[Entity[Abtest]]] = {
    val timeQuery = time.fold(Json.obj())(abtests.byTime)
    abTestDao.find(
      timeQuery
        ++ Json.obj(
          "specialization" -> specialization.toString
        )
    )
  }

  def removeAllOverrides(featureName: FeatureName): F[Feature] =
    for {
      feature <- featureDao.byName(featureName)
      updated <- featureDao.update(
        feature.lens(_.data.overrides).set(Map())
      )
    } yield updated.data

  def getFeature(featureName: FeatureName): F[Feature] =
    featureDao.byName(featureName).map(_.data)

  def getGroups(
      userId: UserId,
      time: Option[OffsetDateTime],
      userTags: List[Tag]
    ): F[Map[FeatureName, GroupName]] =
    validateUserId(userId) >>
      getGroupAssignmentsOf(
        UserGroupQuery(Some(userId), time, userTags)
      ).map(toGroups)

  def terminate(testId: TestId): F[Option[Entity[Abtest]]] =
    for {
      now <- nowF
      test <- getTest(testId)
      r <- test.data.statusAsOf(now) match {
        case Abtest.Status.Scheduled =>
          delete(testId).as(None)
        case Abtest.Status.InProgress =>
          nowF
            .flatMap { now =>
              abTestDao
                .update(
                  test
                    .lens(_.data.end)
                    .set(Some(now))
                )
            }
            .map(Option.apply)
        case Abtest.Status.Expired =>
          F.pure(Some(test))
      }
    } yield r

  private def delete(testId: TestId): F[Unit] =
    abTestDao.remove(testId)

  /**
    *
    * @param testId
    * @param auto create a new test if the current one of the testId is no longer mutable
    * @param f
    * @return
    */
  private def updateAbtestTrivial(
      testId: TestId,
      auto: Boolean
    )(f: Abtest => F[Abtest]
    ): F[Entity[Abtest]] = {
    for {
      now <- nowF
      candidate <- abTestDao.get(testId)
      test <- candidate.data
        .isScheduled(now)
        .fold(
          F.pure(candidate),
          if (auto) {
            if (candidate.data.statusAsOf(now) == Status.Expired)
              CannotUpdateExpiredTest(candidate.data.end.get) //this should be safe since it's already expired and thus must have an end date
                .raiseError[F, Entity[Abtest]]
            else
              create(
                candidate.data.toSpec
                  .copy(
                    start = now.atOffset(ZoneOffset.UTC),
                    end = candidate.data.end.map(_.atOffset(ZoneOffset.UTC))
                  ),
                auto = true
              )
          } else
            CannotChangePastTest(candidate.data.start)
              .raiseError[F, Entity[Abtest]]
        )
      toUpdate <- f(test.data).map(t => test.copy(data = t))
      updated <- abTestDao.update(toUpdate)
    } yield updated
  }

  def updateUserMetaCriteria(
      testId: TestId,
      userMetaCriteria: UserMetaCriteria,
      auto: Boolean
    ): F[Entity[Abtest]] =
    updateAbtestTrivial(testId, auto)(
      _.copy(userMetaCriteria = userMetaCriteria).pure[F]
    )

  def addGroupMetas(
      testId: TestId,
      metas: Map[GroupName, GroupMeta],
      auto: Boolean
    ): F[Entity[Abtest]] =
    errorToF(metas.isEmpty.option(EmptyGroupMeta)) *>
      updateAbtestTrivial(testId, auto) { test =>
        errorsOToF(
          metas.keys.toList.map(
            gn =>
              (!test.groups.exists(_.name === gn))
                .option(Error.GroupNameDoesNotExist(gn))
          )
        ).as(
          test.copy(
            groups = test.groups.map { group =>
              group.copy(
                meta =
                  if (metas.nonEmpty)
                    metas.get(group.name) orElse group.meta orElse test.groupMetas
                      .get(group.name)
                  else
                    None
              )
            },
            groupMetas = Map()
          )
        )
      }

  def removeGroupMetas(
      testId: TestId,
      auto: Boolean
    ): F[Entity[Abtest]] =
    updateAbtestTrivial(testId, auto) { test =>
      test
        .copy(
          groups = test.groups.map(_.copy(meta = None)),
          groupMetas = Map()
        )
        .pure[F]
    }

  def getGroupsWithMeta(query: UserGroupQuery): F[UserGroupQueryResult] =
    validate(query) >> {
      getGroupAssignmentsOfWithAt(query).map {
        case (groupAssignments, at) =>
          val metas = groupAssignments.collect {
            case (fn, AssignmentWithMeta(gn, Some(meta))) => fn -> meta
          }
          UserGroupQueryResult(
            at,
            toGroups(groupAssignments),
            metas
          )
      }
    }

  def cleanUp(
      featureName: FeatureName,
      before: OffsetDateTime
    ): F[Int] =
    getTestsByFeature(featureName).flatMap { tests =>
      val toRemove =
        tests.filter(_.data.end.fold(false)(_.isBefore(before.toInstant)))
      toRemove.traverse(t => delete(t._id)).map(_.size)
    }

  /**
    * bypassing the eligibility control
    */
  def getGroupAssignments(
      ids: List[UserId],
      featureName: FeatureName,
      at: OffsetDateTime
    ): F[List[(UserId, GroupName)]] =
    for {
      test <- getTestByFeature(featureName, at)
      feature <- featureDao.findOne('name -> featureName)
    } yield (ids.flatMap { uid =>
      Bucketing.getGroup(uid, test.data).map((uid, _))
    }.toMap ++ feature.data.overrides).toList

  private def obtainLock(
      feature: Entity[Feature],
      gracePeriod: Option[FiniteDuration]
    ): F[Boolean] = {
    import TimeUtil.InstantOps
    val noLock: (String, JsValueWrapper) = "lockedAt" -> Json.obj("$exists" -> false)
    nowF.flatMap { now =>
      val lockCheck: (String, JsValueWrapper) =
        gracePeriod.fold(
          noLock
        ) { gp =>
          "$or" -> Json.arr(
            Json.obj(noLock),
            Json.obj(
              "lockedAt" ->
                Json.obj("$lt" -> now.plusDuration(gp.inverse))
            )
          )
        }

      featureDao
        .update(
          Json.obj(
            "name" -> feature.data.name,
            lockCheck
          ),
          feature.lens(_.data.lockedAt).set(Some(now)),
          upsert = false
        )
    }

  }
  private def releaseLock(feature: Entity[Feature]): F[Entity[Feature]] =
    featureDao
      .update(feature.lens(_.data.lockedAt).set(None))

  private def createWithoutLock(
      testSpec: AbtestSpec,
      auto: Boolean
    ): F[Entity[Abtest]] =
    for {
      _ <- validateForCreation(testSpec)
      created <- if (auto)
        createAuto(testSpec)
      else
        for {
          lastOne <- lastTest(testSpec)
          r <- lastOne
            .filter(_.data.endsAfter(testSpec.start))
            .fold(
              doCreate(testSpec, lastOne)
            )(le => F.raiseError(ConflictTest(le)))
        } yield r

    } yield created

  private def addTestWithLock(
      fn: FeatureName,
      gracePeriod: Option[FiniteDuration] = Some(30.seconds)
    )(add: F[Entity[Abtest]]
    ): F[Entity[Abtest]] =
    for {
      f <- ensureFeature(fn)
      _ <- obtainLock(f, gracePeriod)
        .adaptErr {
          case e => ConflictCreation(fn, e.getMessage)
        }
        .ensure(
          ConflictCreation(fn, "Another process is adding a test to this feature")
        )(identity)
      attempt <- F.attempt(add)
      _ <- releaseLock(f)
      t <- F.fromEither(attempt)
    } yield t

  private def createAuto(ts: AbtestSpec): F[Entity[Abtest]] =
    for {
      lastOne <- lastTest(ts)
      now <- nowF
      r <- lastOne.fold(doCreate(ts, None)) { lte =>
        val lt = lte.data
        lt.statusAsOf(ts.start) match {
          case Abtest.Status.Scheduled => //when the existing test is ahead of the new test
            terminate(lte._id) >> createWithoutLock(
              ts,
              false
            )
          case Abtest.Status.InProgress => //when the exiting test has overlap with the new test
            tryUpdate(lte, ts, now).getOrElse(
              continueWith(ts, lte)
            )
          case Abtest.Status.Expired => //when the existing test is before the new test.
            doCreate(ts, lastOne)
        }
      }
    } yield r

  private def newSalt =
    Random.alphanumeric.take(10).mkString

  private def continueWith(
      testSpec: AbtestSpec,
      continueFrom: Entity[Abtest]
    ): F[Entity[Abtest]] =
    for {
      _ <- errorToF(
        continueFrom.data.end
          .filter(_.isBefore(testSpec.startI))
          .map(ContinuationGap(_, testSpec.startI))
      )
      _ <- errorToF(
        continueFrom.data.start
          .isAfter(testSpec.startI)
          .option(
            ContinuationBefore(
              continueFrom.data.start,
              testSpec.startI
            )
          )
      )
      updatedContinueFrom <- abTestDao.update(
        continueFrom
          .lens(_.data.end)
          .set(Some(testSpec.start.toInstant))
      )
      created <- doCreate(
        testSpec,
        Some(updatedContinueFrom)
      )
    } yield created

  private def toGroups(
      assignments: Map[FeatureName, AssignmentResult]
    ): Map[FeatureName, GroupName] =
    assignments.toList.collect {
      case (k, AssignmentWithMeta(groupName, _)) => (k, groupName)
    }.toMap

  private def getGroupAssignmentsOf(
      query: UserGroupQuery
    ): F[Map[FeatureName, AssignmentResult]] =
    getGroupAssignmentsOfWithAt(query).map(_._1)

  private def getGroupAssignmentsOfWithAt(
      query: UserGroupQuery
    ): F[(Map[FeatureName, AssignmentResult], Instant)] =
    for {
      td <- getAllTestsCachedWithAt(query.at.map(_.toInstant))
      assignment <- AssignGroups.assign[F](td, query, staleTimeout)
    } yield (assignment, td.at)

  private def testFromSpec(
      spec: AbtestSpec,
      basedOn: Option[Entity[Abtest]]
    ): Abtest = {
    val groupsWithLegacyMeta: List[model.Group] =
      spec.groups.map { group =>
        group.copy(
          meta = group.meta orElse
            spec.groupMetas.get(group.name) orElse
            basedOn.flatMap(_.data.getGroupMetas.get(group.name)) //ensures that by default spec doesn't remove group Meta
        )
      }
    spec
      .to[Abtest]
      .set(
        start = spec.startI,
        end = spec.endI,
        groups = groupsWithLegacyMeta,
        ranges = Bucketing.newRanges(
          spec.groups,
          basedOn
            .map(_.data.ranges)
            .getOrElse(Map.empty)
        ),
        salt =
          (if (spec.reshuffle) Option(newSalt)
           else basedOn.flatMap(_.data.salt)),
        groupMetas = Map.empty[String, GroupMeta]
      )

  }

  private def doCreate(
      spec: AbtestSpec,
      inheritFrom: Option[Entity[Abtest]]
    ): F[Entity[Abtest]] = {
    for {
      newTest <- abTestDao.insert(
        testFromSpec(spec, inheritFrom)
      )
      _ <- ensureFeature(spec.feature)
    } yield newTest
  }

  private def groupsEqualSizes(
      ga: List[model.Group],
      gb: List[model.Group]
    ) =
    ga.map(_.copy(meta = None)).toSet ==
      gb.map(_.copy(meta = None)).toSet

  private def tryUpdate(
      toUpdate: Entity[Abtest],
      updateWith: AbtestSpec,
      now: Instant
    ): Option[F[Entity[Abtest]]] = {

    if (toUpdate.data.isScheduled(now) && groupsEqualSizes(
          toUpdate.data.groups,
          updateWith.groups
        ))
      Some(
        abTestDao.update(
          toUpdate.copy(
            data = testFromSpec(updateWith, Some(toUpdate))
              .copy(groups = updateWith.groups, ranges = toUpdate.data.ranges)
          )
        )
      )
    else None
  }

  def ensure(
      boolean: Boolean,
      err: => Error
    ): F[Unit] =
    F.unit.ensure(err)(_ => boolean)

  private def validateForCreation(testSpec: AbtestSpec): F[Unit] =
    errorsOFFToF(
      nowF.map(
        now =>
          List(
            testSpec.groups.isEmpty
              .option(Error.EmptyGroups),
            (testSpec.groups.map(_.size).sum > 1.000000001)
              .option(
                Error.InconsistentGroupSizes(
                  testSpec.groups.map(_.size)
                )
              ),
            testSpec.end
              .filter(_.isBefore(testSpec.start))
              .as(Error.InconsistentTimeRange),
            testSpec.start.toInstant
              .isBefore(now.minusSeconds(60))
              .option(Error.CannotScheduleTestBeforeNow),
            (testSpec.groups
              .map(_.name)
              .distinct
              .length != testSpec.groups.length)
              .option(Error.DuplicatedGroupName),
            testSpec.groups
              .exists(_.name.length >= 256)
              .option(Error.GroupNameTooLong),
            (!testSpec.feature.matches("[-_.A-Za-z0-9]+"))
              .option(Error.InvalidFeatureName),
            (!testSpec.alternativeIdName
              .fold(true)(_.matches("[-_.A-Za-z0-9]+")))
              .option(Error.InvalidAlternativeIdName)
          )
      )
    )
  private def validate(userGroupQuery: UserGroupQuery): F[Unit] =
    userGroupQuery.userId.fold(F.unit)(validateUserId)

  private def validateUserId(userId: UserId): F[Unit] =
    List(userId.isEmpty.option(Error.EmptyUserId))

  private def ensureFeature(name: FeatureName): F[Entity[Feature]] =
    featureDao.byName(name).recoverWith {
      case Error.NotFound(_) =>
        featureDao.insert(Feature(name, None, Map()))
    }

  private implicit def errorsToF(possibleErrors: List[ValidationError]): F[Unit] =
    possibleErrors.toNel
      .map[Error](ValidationErrors)
      .fold(F.pure(()))(F.raiseError)

  private implicit def errorsOFFToF(
      possibleErrorsF: F[List[Option[ValidationError]]]
    ): F[Unit] =
    possibleErrorsF.flatMap(errorsOToF)

  private implicit def errorsOToF(
      possibleErrors: List[Option[ValidationError]]
    ): F[Unit] =
    errorsToF(possibleErrors.flatten)

  private implicit def errorToF(possibleError: Option[ValidationError]): F[Unit] =
    List(possibleError)

  private def lastTest(testSpec: AbtestSpec): F[Option[Entity[Abtest]]] =
    getTestByFeature(testSpec.feature)
      .map(Option.apply)
      .recover { case NotFound(_) => None }

}
