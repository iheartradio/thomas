package com.iheart.thomas.stream
import fs2.Stream

trait MessageSubscriber[F[_], Message] {
  def subscribe: Stream[F, Message]
}
