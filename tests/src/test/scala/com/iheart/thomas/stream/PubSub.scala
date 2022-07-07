package com.iheart.thomas.stream

import cats.effect.Concurrent
import cats.syntax.all._
import fs2.Stream
import fs2.concurrent.Topic
import org.typelevel.jawn.ast.JValue

class PubSub[F[_]: Concurrent] private (topic: Topic[F, JValue])
    extends MessageSubscriber[F, JValue] {

  def publish(js: JValue*): F[Unit] = js.toList.traverse(topic.publish1).void
  def publishS(js: JValue*): Stream[F, Unit] = Stream.eval(publish(js: _*))

  def subscribe: Stream[F, JValue] =
    topic.subscribe(10) // topic returns always returns the last message
}

object PubSub {
  def create[F[_]: Concurrent]: F[PubSub[F]] =
    Topic[F, JValue].map(new PubSub[F](_))
}
