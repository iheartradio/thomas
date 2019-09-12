/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package client

import cats.effect.{ContextShift, IO}
import com.iheart.thomas.abtest.model.UserGroupQuery

import collection.JavaConverters._

class JavaAbtestAssignments private (serviceUrl: String, asOf: Option[Long]) {
  private val time = asOf.map(TimeUtil.toDateTime)
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val csIo: ContextShift[IO] = IO.contextShift(global)
  val assignGroups = AbtestClient.assignGroups[IO](serviceUrl, time).unsafeRunSync()

  def assignments(
      userId: String,
      tags: java.util.ArrayList[String],
      meta: java.util.Map[String, String],
      features: java.util.ArrayList[String]
  ): java.util.List[(FeatureName, GroupName)] = {
    assignGroups
      .assign(
        UserGroupQuery(Some(userId),
                       time,
                       tags.asScala.toList,
                       meta.asScala.toMap,
                       features = features.asScala.toList))
      ._2
      .map {
        case (fn, (gn, _)) => (fn, gn)
      }
      .toList
      .asJava
  }

  def assignments(
      userIds: java.util.List[UserId],
      feature: String,
      tags: java.util.List[String],
      meta: java.util.Map[String, String]
  ): java.util.List[(UserId, GroupName)] = {
    val tagsL = tags.asScala.toList
    val metaS = meta.asScala.toMap
    val features = List(feature)
    userIds.asScala.toList.flatMap { userId =>
      assignGroups
        .assign(UserGroupQuery(Some(userId), time, tagsL, metaS, features))
        ._2
        .get(feature)
        .map(_._1)
        .map((userId, _))
    }.asJava
  }

  def assignments(
      userIds: java.util.List[UserId],
      feature: String,
  ): java.util.List[(UserId, GroupName)] =
    assignments(userIds,
                feature,
                new java.util.ArrayList[String](),
                new java.util.HashMap[String, String]())

  def assignments(
      userId: String
  ): java.util.List[(FeatureName, GroupName)] =
    assignments(userId,
                new java.util.ArrayList[String](),
                new java.util.HashMap[String, String](),
                new java.util.ArrayList[String]())

  def assignments(
      userId: String,
      features: java.util.ArrayList[String]
  ): java.util.List[(FeatureName, GroupName)] =
    assignments(userId,
                new java.util.ArrayList[String](),
                new java.util.HashMap[String, String](),
                features)
}

object JavaAbtestAssignments {
  def create(serviceUrl: String): JavaAbtestAssignments =
    new JavaAbtestAssignments(serviceUrl, None)
  def create(serviceUrl: String, asOf: Long): JavaAbtestAssignments =
    new JavaAbtestAssignments(serviceUrl, Some(asOf))
}
