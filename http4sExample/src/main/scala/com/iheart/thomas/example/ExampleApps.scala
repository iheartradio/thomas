package com.iheart.thomas.example

import cats.effect._
import com.iheart.thomas.http4s.AdminUI
import com.iheart.thomas.http4s.abtest.AbtestService
import lihua.dynamo.testkit.LocalDynamo
import org.http4s.server.blaze._

import scala.concurrent.ExecutionContext.Implicits.global
object ExampleAbtestServerApp extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    AbtestService.fromMongo[IO]().use { s =>
      BlazeServerBuilder[IO](global)
        .bindHttp(8080, "0.0.0.0")
        .withHttpApp(s.routes)
        .serve
        .compile
        .drain
        .as(ExitCode.Success)
    }
}

object ExampleAbtestAdminUIApp extends IOApp {
  import com.iheart.thomas.testkit.ExampleArmParse._

  def run(args: List[String]): IO[ExitCode] = {
    LocalDynamo
      .client[IO]
      .flatMap(implicit c => AdminUI.serverResourceAutoLoadConfig[IO])
      .use(_ => IO.never)

  }

}
