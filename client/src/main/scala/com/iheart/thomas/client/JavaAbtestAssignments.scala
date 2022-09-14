/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package client

import java.time.{Instant, ZoneOffset}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.iheart.thomas.abtest.AssignGroups
import com.iheart.thomas.abtest.AssignGroups.AssignmentResult
import com.iheart.thomas.abtest.model.UserGroupQuery

import scala.jdk.CollectionConverters._
import scala.concurrent.duration.{Duration, DurationInt}

class JavaAbtestAssignments private (
    serviceUrl: String,
    asOf: Option[Long]) {
  private val time = asOf.map(Instant.ofEpochSecond).getOrElse(Instant.now)
  implicit val nowF: IO[Instant] = IO.delay(Instant.now)
  implicit val ex: concurrent.ExecutionContext = global.compute
  val testData =
    AbtestClient.testsData[IO](serviceUrl, time, Some(12.hours)).unsafeRunSync()

  def assignments(
      userId: String,
      tags: java.util.List[String],
      meta: java.util.Map[String, String],
      features: java.util.List[String]
    ): java.util.Map[FeatureName, GroupName] = {
    AssignGroups
      .assign[IO](
        testData,
        UserGroupQuery(
          Some(userId),
          Some(time.atOffset(ZoneOffset.UTC)),
          tags.asScala.toList,
          meta.asScala.toMap,
          features = features.asScala.toList
        ),
        Duration.Zero
      )
      .map(_.collect { case (fn, AssignmentResult(gn, _)) => (fn, gn) }.asJava)
      .unsafeRunSync()

  }

  def assignments(userId: String): java.util.Map[FeatureName, GroupName] =
    assignments(
      userId,
      new java.util.ArrayList[String](),
      new java.util.HashMap[String, String](),
      new java.util.ArrayList[String]()
    )

  def assignments(
      userId: String,
      features: java.util.List[String]
    ): java.util.Map[FeatureName, GroupName] =
    assignments(
      userId,
      new java.util.ArrayList[String](),
      new java.util.HashMap[String, String](),
      features
    )
}

object JavaAbtestAssignments {
  def create(serviceUrl: String): JavaAbtestAssignments =
    new JavaAbtestAssignments(serviceUrl, None)
  def create(
      serviceUrl: String,
      asOf: Long
    ): JavaAbtestAssignments =
    new JavaAbtestAssignments(serviceUrl, Some(asOf))
}
