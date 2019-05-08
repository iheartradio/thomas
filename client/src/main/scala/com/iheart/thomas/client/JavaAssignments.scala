/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package client

import cats.effect.{ContextShift, IO}
import com.iheart.thomas.model.UserGroupQuery

import collection.JavaConverters._

class JavaAssignments private (serviceUrl: String, asOf: Option[Long]) {
  private val time = asOf.map(TimeUtil.toDateTime)
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val csIo: ContextShift[IO] = IO.contextShift(global)
  val assignGroups = Client.assignGroups[IO](serviceUrl, time).unsafeRunSync()

  def assignments(
    userId: String,
    tags:   java.util.ArrayList[String],
    meta:   java.util.Map[String, String],
    features: java.util.ArrayList[String]
  ): java.util.Map[String, String] = {
    assignGroups.assign(UserGroupQuery(Some(userId), time, tags.asScala.toList, meta.asScala.toMap, features = features.asScala.toList))._2.map {
      case (fn, (gn, _)) => (fn, gn)
    }.asJava
  }

  def assignments(
    userId: String
  ): java.util.Map[String, String] =
    assignments(userId, new java.util.ArrayList[String](), new java.util.HashMap[String, String](), new java.util.ArrayList[String]())

  def assignments(
    userId: String,
    features: java.util.ArrayList[String]
  ): java.util.Map[String, String] =
    assignments(userId, new java.util.ArrayList[String](), new java.util.HashMap[String, String](), features)
}

object JavaAssignments {
  def create(serviceUrl: String): JavaAssignments = new JavaAssignments(serviceUrl, None)
  def create(serviceUrl: String, asOf: Long): JavaAssignments = new JavaAssignments(serviceUrl, Some(asOf))
}
