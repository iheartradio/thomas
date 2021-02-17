package com.iheart.thomas
package abtest

import java.time.{Instant, OffsetDateTime, ZoneOffset}

import cats.MonadError
import cats.effect.IO
import com.iheart.thomas.GroupName
import com.iheart.thomas.abtest.model._

import scala.util.Random

object TestUtils {
  type F[A] = IO[A]
  val F = MonadError[IO, Throwable]
  def fakeAb: AbtestSpec = fakeAb()

  def group(
      name: GroupName,
      size: GroupSize
    ) =
    Group(name, size, None)

  def fakeAb(
      start: Int = 0,
      end: Int = 100,
      feature: String = "AMakeUpFeature" + Random.alphanumeric.take(5).mkString,
      alternativeIdName: Option[MetaFieldName] = None,
      groups: List[Group] = List(Group("A", 0.5, None), Group("B", 0.5, None)),
      userMetaCriteria: UserMetaCriteria = None,
      segRanges: List[GroupRange] = Nil,
      requiredTags: List[Tag] = Nil
    ): AbtestSpec =
    AbtestSpec(
      name = "test",
      author = "kai",
      feature = feature,
      start = OffsetDateTime.now.plusDays(start.toLong),
      end = Some(OffsetDateTime.now.plusDays(end.toLong)),
      groups = groups,
      alternativeIdName = alternativeIdName,
      userMetaCriteria = userMetaCriteria,
      segmentRanges = segRanges,
      requiredTags = requiredTags
    )

  def randomUserId = Random.alphanumeric.take(10).mkString

  lazy val tomorrow = Some(OffsetDateTime.now.plusDays(1))

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

  def withAlg[A](f: AbtestAlg[F] => F[A]): F[A] =
    testkit.Resources.apis.map(_._3).use(f)

  def q(
      userId: UserId,
      at: Option[OffsetDateTime] = None,
      meta: UserMeta = Map(),
      eligibilityControlFilter: EligibilityControlFilter =
        EligibilityControlFilter.All
    ) =
    UserGroupQuery(
      Some(userId),
      at = at,
      meta = meta,
      eligibilityControlFilter = eligibilityControlFilter
    )
}
