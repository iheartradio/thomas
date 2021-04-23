package com.iheart.thomas
package example

import cats.effect._
import com.iheart.thomas.analysis.{AccumulativeKPI, PerUserSamples}
import com.iheart.thomas.http4s.AdminUI
import com.iheart.thomas.http4s.abtest.AbtestService
import com.iheart.thomas.stream.KPIEventQuery
import com.iheart.thomas.tracking.EventLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import testkit.LocalDynamo
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
  implicit val mockEventQuery: KPIEventQuery[IO, AccumulativeKPI, PerUserSamples] =
    KPIEventQuery.alwaysFail[IO, AccumulativeKPI, PerUserSamples]

  implicit val logger = EventLogger.catsLogger(Slf4jLogger.getLogger[IO])

  def run(args: List[String]): IO[ExitCode] = {
    LocalDynamo
      .client[IO]()
      .flatMap(implicit c => AdminUI.serverResourceAutoLoadConfig[IO])
      .use(_ => IO.never)

  }

}
