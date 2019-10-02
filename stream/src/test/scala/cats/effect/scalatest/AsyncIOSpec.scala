package cats.effect.scalatest

import cats.effect.{ContextShift, IO, Timer}
import org.scalatest.freespec.AsyncFreeSpec

import scala.concurrent.ExecutionContext

trait AsyncIOSpec
    extends AsyncFreeSpec
    with AssertingSyntax
    with EffectTestSupport {
  override val executionContext: ExecutionContext = ExecutionContext.global
  implicit val ioContextShift: ContextShift[IO] =
    IO.contextShift(executionContext)
  implicit val ioTimer: Timer[IO] = IO.timer(executionContext)
}
