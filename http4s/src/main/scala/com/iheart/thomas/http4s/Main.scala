package com.iheart
package thomas
package http4s

import cats.effect._

import org.http4s.server.blaze._
import scala.concurrent.ExecutionContext.Implicits.global
import cats.implicits._
import scalacache.CatsEffect.modes._
object Main extends ExampleHtt4sApp

trait ExampleHtt4sApp extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    AbtestService.mongo[IO].use { s =>
      BlazeServerBuilder[IO]
        .bindHttp(8080, "localhost")
        .withHttpApp(s.routes)
        .serve
        .compile
        .drain
        .as(ExitCode.Success)
    }
}



