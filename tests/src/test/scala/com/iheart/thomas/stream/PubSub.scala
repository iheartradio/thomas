package com.iheart.thomas.stream

import cats.effect.Concurrent
import fs2.concurrent.Topic
import org.typelevel.jawn.ast.JValue
import fs2.Stream
import cats.implicits._

class PubSub[F[_]: Concurrent] private (topic: Topic[F, JValue])
    extends MessageSubscriber[F, JValue] {

  def publish(js: JValue*): Stream[F, Unit] = topic.publish(Stream(js: _*))

  def subscribe: Stream[F, JValue] = topic.subscribe(10)
}

object PubSub {
  def create[F[_]: Concurrent](initValue: JValue): F[PubSub[F]] =
    Topic[F, JValue](initValue).map(new PubSub[F](_))
}
