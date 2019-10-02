/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package client

import java.time.{Duration, LocalDateTime}

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.Matchers
import collection.JavaConverters._

class JavaAPISuite extends AnyFunSuite with Matchers {

  test("integration") {
    val host =
      sys.env
        .get("ABTEST_HOST_ROOT_PATH")
        .getOrElse("http://localhost:9000/internal")
    val api =
      JavaAbtestAssignments.create(s"${host}/testsWithFeatures")
    val begin = LocalDateTime.now
    println(begin + " --- Begin")
    val n = 1000000
    val userIds = (1 to n).map(_.toString).toList.asJava
    val _ = api.assignments(
      userIds,
      new java.util.ArrayList[String](),
      new java.util.HashMap[String, String](),
      new java.util.ArrayList[String]("Radio_Model")
    )

    val span = Duration.between(begin, LocalDateTime.now)

    println("spent" + span)
    println(n / span.toMillis + " assignments / ms")
  }
}
