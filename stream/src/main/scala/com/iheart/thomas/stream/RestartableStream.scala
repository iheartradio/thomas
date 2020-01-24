package com.iheart.thomas.stream

import cats.effect.ConcurrentEffect
import fs2.concurrent.SignallingRef
import fs2.Stream
import cats.implicits._
object RestartableStream {

  def restartable[F[_], A](
      stream: => Stream[F, A]
    )(implicit F: ConcurrentEffect[F]
    ): F[(Stream[F, A], SignallingRef[F, Boolean])] = {
    SignallingRef[F, Boolean](false).map { pauseSignal =>
      val resultStream =
        Stream
          .eval(pauseSignal.get)
          .repeat
          .filterNot(identity)
          .pauseWhen(pauseSignal) *>
          Stream
            .suspend(stream)
            .onFinalize(pauseSignal.set(true))
            .interruptWhen(pauseSignal)

      (resultStream, pauseSignal)
    }
  }
}
