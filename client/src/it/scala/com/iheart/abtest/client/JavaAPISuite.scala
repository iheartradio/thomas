/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.abtest
package client

import org.scalatest.{FunSuite, Matchers}

class JavaAPISuite extends FunSuite with Matchers {

  test("integration") {
    val api = JavaAPI.create("qa")

    println(
      (1 to 10).map { i =>
        api.assignments(i.toString)
      }.mkString("\n")
    )
  }
}
