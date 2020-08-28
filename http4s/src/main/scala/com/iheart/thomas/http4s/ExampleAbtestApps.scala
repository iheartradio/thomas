package com.iheart
package thomas
package http4s

import cats.effect._
import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.Router
import org.http4s.server.blaze._

import scala.concurrent.ExecutionContext.Implicits.global

object ExampleAbtestServerApp extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    AbtestService.fromMongo[IO].use { s =>
      BlazeServerBuilder[IO](global)
        .bindHttp(8080, "localhost")
        .withHttpApp(s.routes)
        .serve
        .compile
        .drain
        .as(ExitCode.Success)
    }
}

object ExampleAbtestAdminUIApp extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    AbtestAdminUI.fromMongo[IO].use { s =>
      BlazeServerBuilder[IO](global)
        .bindHttp(8080, "localhost")
        .withHttpApp(
          Router("/admin/" -> s.routes).orNotFound
        )
        .serve
        .compile
        .drain
        .as(ExitCode.Success)
    }
}
