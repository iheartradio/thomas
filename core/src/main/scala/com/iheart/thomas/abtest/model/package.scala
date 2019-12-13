/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package abtest

import java.time.OffsetDateTime

import cats.implicits._
import _root_.play.api.libs.json.JsObject
import lihua.EntityId
import java.time.Instant

package object model {
  type TestId = EntityId
  type TestName = String
  type MetaFieldName = String
  type Overrides = Map[UserId, GroupName]
  type GroupRanges = Map[GroupName, List[GroupRange]]
  type GroupMeta = JsObject
  type GroupMetas = Map[GroupName, GroupMeta]
  type GroupSize = BigDecimal
  type Tag = String
  type UserMeta = Map[MetaFieldName, String]
}

package model {

  object GroupSize {
    val One = BigDecimal(1)
    val Zero = BigDecimal(0)
  }

  import cats.Eq

  /**
    * Internal representation of an A/B test, the public representation is [[Abtest]]
    */
  case class Abtest(
      name: TestName,
      feature: FeatureName,
      author: String,
      start: Instant,
      end: Option[Instant],
      groups: List[Group],
      ranges: GroupRanges,
      requiredTags: List[Tag] = Nil,
      alternativeIdName: Option[MetaFieldName] = None,
      matchingUserMeta: UserMeta = Map(),
      salt: Option[String] = None,
      segmentRanges: List[GroupRange] = Nil,
      groupMetas: GroupMetas = Map(),
      specialization: Option[Abtest.Specialization] = None) {

    def statusAsOf(time: OffsetDateTime): Abtest.Status = statusAsOf(time.toInstant)

    def statusAsOf(time: Instant): Abtest.Status =
      if (time isBefore start)
        Abtest.Status.Scheduled
      else if (end.fold(false)(_.isBefore(time)))
        Abtest.Status.Expired
      else
        Abtest.Status.InProgress

    def getGroup(groupName: GroupName): Option[Group] =
      groups.find(_.name == groupName)

    def canChange: Boolean =
      statusAsOf(OffsetDateTime.now) === Abtest.Status.Scheduled

    def endsAfter(time: OffsetDateTime) =
      end.fold(true)(_.isAfter(time.toInstant))

    def idToUse(ui: UserInfo): Option[String] =
      alternativeIdName.fold(ui.userId)(ui.meta.get)
  }

  /**
    * Data used to create an A/B tests
    *
    * @param name name of the test, it's more like a note/description. It is NOT an identifier.
    * @param feature feature name of the treatment. This is an identifier with which feature code can determine for each user which treatment they get.
    * @param author author name. Can be used for ownership
    * @param start scheduled start of the test
    * @param end scheduled end of the test, optional, if not given the test will last indefinitely
    * @param groups group definitions. group sizes don't have to add up to 1, but they cannot go beyond 1. If the sum group size is less than 1, it means that there is a portion (1 - the sum group size) of users won't be the in tests at all, you could make this group your control group.
    * @param requiredTags an array of string tags for eligibility control. Once set, only users having these tags (tags are passed in group assignment inquiry requests) are eligible for this test.
    * @param alternativeIdName by default Thomas uses the "userId" field passed in the group assignment inquiry request as the unique identification for segmenting users. In some A/B test cases, e.g. some features in the user registration process, a user Id might not be given yet. In such cases, you can choose to use any user meta field as the unique identification for users.
    * @param matchingUserMeta this is more advanced eligibility control. You can set a field and regex pair to match user meta. Only users whose metadata field value matches the regex are eligible for the experiment.
    * @param reshuffle by default Thomas will try to keep the user assignment consistent between different versions of experiments of a feature. Setting this field to true will redistribute users again among all groups.
    * @param segmentRanges his field is used for creating mutually exclusive tests. When Thomas segments users into different groups, it hashes the user Id to a number between 0 and 1. If an A/B test is set with a set of segment ranges, then only hashes within that range will be eligible for that test. Thus if two tests have non-overlapping segment ranges, they will be mutually exclusive, i.e. users who eligible for one will not be eligible for the other.
    */
  case class AbtestSpec(
      name: TestName,
      feature: FeatureName,
      author: String,
      start: OffsetDateTime,
      end: Option[OffsetDateTime],
      groups: List[Group],
      requiredTags: List[Tag] = Nil,
      alternativeIdName: Option[MetaFieldName] = None,
      matchingUserMeta: UserMeta = Map(),
      reshuffle: Boolean = false,
      segmentRanges: List[GroupRange] = Nil,
      groupMetas: GroupMetas = Map(),
      specialization: Option[Abtest.Specialization] = None) {

    val startI = start.toInstant
    val endI = end.map(_.toInstant)
  }

  object Abtest {
    sealed trait Specialization extends Serializable with Product

    object Specialization {
      case object MultiArmBanditConversion extends Specialization
    }

    sealed trait Status extends Serializable with Product

    object Status {
      case object Scheduled extends Status
      case object InProgress extends Status
      case object Expired extends Status
      implicit val eq: Eq[Status] = Eq.fromUniversalEquals
    }

  }

  case class Group(
      name: GroupName,
      size: GroupSize)

  case class GroupRange(
      start: BigDecimal,
      end: BigDecimal) {
    assert(
      start >= 0 && end <= 1 && start <= end,
      s"Invalid Range $start-$end, must be between 0 and 1"
    )
    val size = end - start
    def contains(that: GroupRange): Boolean =
      start <= that.start && that.end <= end

    def contains(hashValue: Double): Boolean =
      start <= hashValue && hashValue <= end
  }

  case class Feature(
      name: FeatureName,
      description: Option[String],
      overrides: Overrides,
      overrideEligibility: Boolean = false,
      locked: Boolean = false)

  case class UserGroupQuery(
      userId: Option[UserId],
      at: Option[OffsetDateTime] = None,
      tags: List[Tag] = Nil,
      meta: UserMeta = Map(),
      features: List[FeatureName] = Nil)

  case class UserInfo(
      userId: Option[UserId],
      meta: UserMeta = Map())

  case class UserGroupQueryResult(
      at: Instant,
      groups: Map[FeatureName, GroupName],
      metas: Map[FeatureName, GroupMeta])
}
