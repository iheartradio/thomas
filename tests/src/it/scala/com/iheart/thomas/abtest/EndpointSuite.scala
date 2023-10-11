package com.iheart.thomas.abtest

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.iheart.thomas.abtest.TestUtils.withAlg
import com.iheart.thomas.http4s.abtest.AbtestService
import com.iheart.thomas.tracking.EventLogger
import fs2._
import org.http4s.Status._
import org.http4s.{Method, Request}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.http4s.implicits.http4sLiteralsSyntax

class EndpointSuite extends AsyncFreeSpec with AsyncIOSpec with Matchers {
  implicit val logger: EventLogger[IO] = EventLogger.catsLogger(Slf4jLogger.getLogger[IO])

  "/users/groups/query endpoint should handle different request bodies appropriately" - {
    "good request body json should return OK" in {
      withAlg { alg =>
        val data: String = """{"meta":{"country":"us"},"userId":"123"}"""
        val body: Stream[IO, Byte] = Stream.emit(data).through(text.utf8.encode)
        new AbtestService(alg).public.orNotFound.run(
          Request(method = Method.POST, uri = uri"/users/groups/query", body = body)
        )
      }.asserting { response =>
        response.status shouldBe Ok
      }
    }

    "empty request body json should return BadRequest" in {
      withAlg { alg =>
        new AbtestService(alg).public.orNotFound.run(
          Request(method = Method.POST, uri = uri"/users/groups/query")
        )
      }.asserting { response =>
        response.status shouldBe BadRequest
      }
    }

    "bad request body json should return BadRequest" in {
      withAlg { alg =>
        val data: String = """{"meta":{"country":"us", "deviceId": {}},"userId":"123"}"""
        val body: Stream[IO, Byte] = Stream.emit(data).through(text.utf8.encode)
        new AbtestService(alg).public.orNotFound.run(
          Request(method = Method.POST, uri = uri"/users/groups/query", body = body)
        )
      }.asserting { response =>
        response.status shouldBe BadRequest
      }
    }
  }
}
