/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package client

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.Matchers

class JavaAPISuite extends AnyFunSuite with Matchers {

  test("integration") {
    val api =
      JavaAbtestAssignments.create("http://localhost:9000/internal/testsWithFeatures")

    println(
      (1 to 10)
        .map { i =>
          api.assignments(i.toString)
        }
        .mkString("\n")
    )
  }
}
