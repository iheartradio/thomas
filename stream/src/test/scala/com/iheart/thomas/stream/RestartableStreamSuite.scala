package com.iheart.thomas.stream

import cats.effect.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import fs2.Stream
import cats.effect.IO
import cats.implicits._
import concurrent.duration._

class RestartableStreamSuite extends AsyncIOSpec with Matchers {

  "RestartableStream" - {
    def stream: Stream[IO, Int] = {
      val count = new java.util.concurrent.atomic.AtomicInteger(0)
      Stream.repeatEval(IO.sleep(100.millis) *> IO.delay(count.getAndIncrement()))
    }
    "can pause" in {
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

      count.asserting(_.last shouldBe (20 +- 4))

    }

    "can restart after pause" in {
      val count = for {
        p <- RestartableStream.restartable(stream)
        (stream, sig) = p
        c <- stream
          .concurrently(
            Stream.sleep(2.seconds) ++
              Stream.eval(sig.set(true)) ++
              Stream.eval(sig.set(false))
          )
          .interruptAfter(5.seconds)
          .compile
          .toVector
      } yield c

      count.asserting(_.last shouldBe (30 +- 5))

    }
  }

}
