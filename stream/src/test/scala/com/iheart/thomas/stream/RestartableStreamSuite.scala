package com.iheart.thomas.stream

import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import fs2.Stream
import cats.effect.IO
import cats.implicits._
import concurrent.duration._
import java.util.concurrent.atomic.AtomicInteger

class RestartableStreamSuite extends AsyncIOSpec with Matchers {

  "RestartableStream" - {

    "can pause" in {
      def stream: Stream[IO, Int] = {
        val count = new AtomicInteger(0)
        Stream.repeatEval(IO.sleep(100.millis) *> IO.delay(count.getAndIncrement()))
      }

      val count = for {
        p <- RestartableStream.restartable(stream)
        (stream, sig) = p
        c <- stream
          .concurrently(
            Stream.sleep(2.seconds) ++ Stream.eval(sig.set(true))
          )
          .interruptAfter(5.seconds)
          .compile
          .toVector
      } yield c

      count.asserting(_.last shouldBe (20 +- 5))

    }

    "can restart after pause" in {
      val startCount = new AtomicInteger(-1)

      def stream: Stream[IO, Int] = {
        val count = new AtomicInteger(0)
        Stream.eval(IO.delay(startCount.incrementAndGet())).flatMap { rs =>
          Stream.repeatEval(
            IO.sleep(100.millis) *> IO.delay(count.getAndIncrement() + (rs * 100))
          )
        }
      }

      val r = for {
        p <- RestartableStream.restartable(stream)
        (stream, sig) = p
        c <- stream
          .concurrently(
            Stream.sleep(1.seconds) ++
              Stream.eval(sig.set(true)) ++
              Stream.sleep(1.seconds) ++
              Stream.eval(sig.set(false))
          )
          .interruptAfter(5.seconds)
          .compile
          .toVector
        rs <- IO.delay(startCount.get())
      } yield (c, rs)

      r.asserting {
        case (count, restartCount) =>
          restartCount shouldBe 1
          count.last shouldBe (130 +- 5)
      }

    }

    "does not create multiple streams" in {
      val count = new AtomicInteger(0)

      def stream: Stream[IO, Unit] = {
        Stream.eval(IO.delay(count.getAndIncrement()).void) ++
          Stream.constant(())
      }

      val r = for {
        p <- RestartableStream.restartable(stream)
        (stream, _) = p
        _ <- stream.interruptAfter(1.seconds).compile.drain
        c <- IO.delay(count.get())
      } yield c
      r.asserting(_ shouldBe 1)
    }

    "does not auto re-create multiple streams after stream interrupts" in {
      val count = new AtomicInteger(0)

      def createStream: Stream[IO, Unit] = {
        Stream.eval(IO.delay(count.getAndIncrement()).void) ++
          Stream.constant[IO, Unit](()).interruptAfter(10.milliseconds)
      }

      val r = for {
        p <- RestartableStream.restartable(createStream)
        (stream, _) = p
        _ <- stream.interruptAfter(3.seconds).compile.drain
        c <- IO.delay(count.get())
      } yield c
      r.asserting(_ shouldBe 1)
    }

    "does not auto re-create multiple streams after stream completes" in {
      val count = new AtomicInteger(0)

      def createStream: Stream[IO, Unit] = {
        Stream.eval(IO.delay(count.getAndIncrement()).void).repeatN(3)
      }

      val r = for {
        p <- RestartableStream.restartable(createStream)
        (stream, _) = p
        _ <- stream.interruptAfter(1.seconds).compile.drain
        c <- IO.delay(count.get())
      } yield c
      r.asserting(_ shouldBe 3)
    }
  }

}
