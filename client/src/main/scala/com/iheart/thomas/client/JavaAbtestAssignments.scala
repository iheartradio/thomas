/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package client

import java.time.{Instant, ZoneOffset}

import cats.Id
import cats.effect.{ContextShift, IO}
import com.iheart.thomas.abtest.AssignGroups
import com.iheart.thomas.abtest.model.UserGroupQuery

import collection.JavaConverters._

class JavaAbtestAssignments private (
    serviceUrl: String,
    asOf: Option[Long]) {
  private val time = asOf.map(Instant.ofEpochSecond)
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val csIo: ContextShift[IO] = IO.contextShift(global)
  val testData =
    AbtestClient.testsWithFeatures[IO](serviceUrl, time).unsafeRunSync()

  def assignments(
      userId: String,
      tags: java.util.List[String],
      meta: java.util.Map[String, String],
      features: java.util.List[String]
    ): java.util.Map[FeatureName, GroupName] = {
    AssignGroups
      .assign[Id](
        testData,
        UserGroupQuery(
          Some(userId),
          time.map(_.atOffset(ZoneOffset.UTC)),
          tags.asScala.toList,
          meta.asScala.toMap,
          features = features.asScala.toList
        )
      )
      .map { case (fn, (gn, _)) => (fn, gn) }
      .asJava
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
