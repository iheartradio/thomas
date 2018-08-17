/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart
package thomas

import java.time.OffsetDateTime

import play.api.libs.json.JsObject

package object model {
  type TestId = String
  type TestName = String
  type FeatureName = String
  type MetaFieldName = String
  type GroupName = String
  type UserId = String
  type Overrides = Map[UserId, GroupName]
  type GroupRanges = Map[GroupName, List[GroupRange]]
  type GroupSize = Double
  type GroupMeta = JsObject
  type Tag = String
  type UserMeta = Map[MetaFieldName, String]
}

package model {

  case class Abtest(
    name:                TestName,
    feature:             FeatureName,
    author:              String,
    start:               OffsetDateTime,
    end:                 Option[OffsetDateTime],
    groups:              List[Group],
    statisticalTestType: Option[StatisticalTestType],
    ranges:              GroupRanges,
    requiredTags:        List[Tag]                   = Nil,
    alternativeIdName:   Option[MetaFieldName]       = None,
    matchingUserMeta:    UserMeta                    = Map(),
    salt:                Option[String]              = None,
    segmentRanges:       List[GroupRange]            = Nil
  ) {
    def statusAsOf(time: OffsetDateTime): Abtest.Status =
      if (time isBefore start)
        Abtest.Status.Scheduled
      else if (end.fold(false)(_.isBefore(time)))
        Abtest.Status.Expired
      else
        Abtest.Status.InProgress

    def endsAfter(time: OffsetDateTime) =
      end.fold(true)(_.isAfter(time))

    def idToUse(query: UserGroupQuery): Option[String] =
      alternativeIdName.fold(query.userId)(query.meta.get)
  }

  /**
   * Data needed for creating an a/b test
   */
  case class AbtestSpec(
    name:                TestName,
    feature:             FeatureName,
    author:              String,
    start:               OffsetDateTime,
    end:                 Option[OffsetDateTime],
    groups:              List[Group],
    statisticalTestType: Option[StatisticalTestType] = None,
    requiredTags:        List[Tag]                   = Nil,
    alternativeIdName:   Option[MetaFieldName]       = None,
    matchingUserMeta:    UserMeta                    = Map(),
    reshuffle:           Boolean                     = false,
    segmentRanges:       List[GroupRange]            = Nil
  )

  object Abtest {
    sealed trait Status extends Serializable with Product

    object Status {
      case object Scheduled extends Status
      case object InProgress extends Status
      case object Expired extends Status
    }
  }

  case class Group(name: GroupName, size: GroupSize)

  case class GroupRange(start: Double, end: Double) {
    assert(start >= 0 && end <= 1 && start <= end, s"Invalid Range $start-$end, must be between 0 and 1")
    val size = end - start
    def contains(that: GroupRange): Boolean =
      start <= that.start && that.end <= end

    def contains(hashValue: Double): Boolean =
      start <= hashValue && hashValue <= end
  }

  case class Feature(
    name:        FeatureName,
    description: Option[String],
    overrides:   Overrides,
    locked:      Boolean        = false
  )

  case class AbtestExtras(
    groupMetas: Map[GroupName, GroupMeta] = Map()
  )

  case class UserGroupQuery(
    userId: Option[UserId],
    at:     Option[OffsetDateTime] = None,
    tags:   List[Tag]              = Nil,
    meta:   UserMeta               = Map()
  )

  case class UserGroupQueryResult(
    at:     OffsetDateTime,
    groups: Map[FeatureName, GroupName],
    metas:  Map[FeatureName, GroupMeta]
  )
}
