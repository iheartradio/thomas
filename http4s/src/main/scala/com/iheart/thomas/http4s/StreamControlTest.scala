package com.iheart.thomas.http4s

import java.time.LocalDateTime
import cats.effect.{Concurrent, ExitCode, IO, IOApp}
import fs2.concurrent.SignallingRef
import fs2.Stream
import cats.implicits._
import org.http4s.HttpRoutes
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.dsl.Http4sDsl
import org.http4s.syntax.all._

import concurrent.duration._
import scala.concurrent.ExecutionContext
object StreamControlTest extends IOApp with Http4sDsl[IO] {
  def run(args: List[String]): IO[ExitCode] =
    banditUpdateController[IO](
      Stream
        .repeatEval(IO.sleep(1.second) *> IO(println(LocalDateTime.now)))
        .interruptAfter(120.seconds)
    ).flatMap { case (runs, pause) =>
      BlazeServerBuilder[IO](ExecutionContext.global)
        .bindHttp(9000, "localhost")
        .withHttpApp(routes(pause).orNotFound)
        .serve
        .concurrently(runs)
        .compile
        .drain
        .as(ExitCode.Success)
    }

  def banditUpdateController[F[_]: Concurrent](stream: Stream[F, Unit]) = {
    SignallingRef[F, Boolean](false).map { paused =>
      val consumerStream = stream.pauseWhen(paused)
      (consumerStream, paused)
    }
  }

  def routes(signallingRef: SignallingRef[IO, Boolean]) =
    HttpRoutes.of[IO] {
      case GET -> Root / "start" => signallingRef.set(false) >> Ok("started")
      case GET -> Root / "stop"  => signallingRef.set(true) >> Ok("stopped")
    }
}
