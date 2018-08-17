/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas.stress
import java.time.{Instant, OffsetDateTime, ZoneId, ZoneOffset}

import io.gatling.core.Predef._
import io.gatling.core.structure.{ChainBuilder, PopulationBuilder}
import io.gatling.http.Predef._

import scala.concurrent.duration._

//import scala.language.postfixOps

class GetGroupsSimulation extends Simulation {

  val userId = 4331241
  val testName = "getGroups"

  // val host = "http://localhost:9000"
  // val host = "http://stg-abtest.ihrcloud.net"
  // val host = "http://abtest.ihrprod.net"
  val host = "https://qa-ampinternal.ihrcloud.net"

  setUp(
    scenario(testName).during(500.seconds) {
      exec(
        http(testName)
          .get(s"$host/internal/api/v3/abtest/users/$userId/tests/groups")
          .check(status.is(200))
      )
    }.inject(rampUsers(10) over (60.seconds))
  ).protocols(http.disableCaching)
    .assertions(
      global.requestsPerSec.gte(1000)
    )

}

