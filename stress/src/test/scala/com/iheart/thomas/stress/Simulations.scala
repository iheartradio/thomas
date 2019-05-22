/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas.stress

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._


class GetGroupsSimulation extends Simulation {

  val userId = 4331241
  val testName = "getGroups"

  val host = "http://localhost:9000"

  setUp(
    scenario(testName).during(500.seconds) {
      exec(
        http(testName)
          .get(s"$host/internal/users/$userId/tests/groups")
          .check(status.is(200))
      )
    }.inject(rampUsers(10) over (60.seconds))
  ).protocols(http.disableCaching)
    .assertions(
      global.requestsPerSec.gte(1000)
    )

}

