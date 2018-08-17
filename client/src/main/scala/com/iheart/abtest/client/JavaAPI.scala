/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.abtest
package client

import cats.Id
import cats.effect.IO
import com.iheart.abtest.model.UserGroupQuery

import collection.JavaConverters._


class JavaAPI(serviceUrl: String, asOf: Option[Long]) {
  private val time = asOf.map(TimeUtil.toDateTime)

  val assignGroups = Client.assignGroups[IO](serviceUrl, time).unsafeRunSync()

  def assignments(
    userId: String,
    tags:   java.util.ArrayList[String],
    meta:   java.util.Map[String, String]
  ): java.util.Map[String, String] = {
    assignGroups.assign(UserGroupQuery(Some(userId), time, tags.asScala.toList, meta.asScala.toMap))._2.map {
      case (fn, (gn, _)) => (fn, gn)
    }.asJava
  }

  def assignments(
    userId: String
  ): java.util.Map[String, String] =
    assignments(userId, new java.util.ArrayList[String](), new java.util.HashMap[String, String]())
}

object JavaAPI {
  def create(serviceUrl: String): JavaAPI = new JavaAPI(serviceUrl, None)
  def create(serviceUrl: String, asOf: Long): JavaAPI = new JavaAPI(serviceUrl, Some(asOf))
}
