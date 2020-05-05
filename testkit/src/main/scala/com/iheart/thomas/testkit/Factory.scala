package com.iheart.thomas.testkit

import java.time.OffsetDateTime

import com.iheart.thomas.abtest.model.{
  AbtestSpec,
  Group,
  GroupMetas,
  GroupRange,
  MetaFieldName,
  Tag,
  UserMeta
}

import scala.util.Random

object Factory {
  val now = OffsetDateTime.now

  def fakeAb(
      start: Int = 0,
      end: Int = 100,
      feature: String = "AMakeUpFeature" + Random.alphanumeric.take(5).mkString,
      alternativeIdName: Option[MetaFieldName] = None,
      groups: List[Group] = List(Group("A", 0.5), Group("B", 0.5)),
      matchingUserMeta: UserMeta = Map(),
      segRanges: List[GroupRange] = Nil,
      requiredTags: List[Tag] = Nil,
      groupMetas: GroupMetas = Map()
    ): AbtestSpec = AbtestSpec(
    name = "test",
    author = "kai",
    feature = feature,
    start = now.plusDays(start.toLong),
    end = Some(now.plusDays(end.toLong)),
    groups = groups,
    alternativeIdName = alternativeIdName,
    userMetaCriteria = matchingUserMeta,
    segmentRanges = segRanges,
    requiredTags = requiredTags,
    groupMetas = groupMetas
  )
}
