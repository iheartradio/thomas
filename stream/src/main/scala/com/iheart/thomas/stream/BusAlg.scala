package com.iheart.thomas.stream

import cats.effect._
import cats.effect.kernel.Concurrent
import cats.implicits._
import fs2.concurrent.Topic
import fs2.{Pipe, Stream}
import org.typelevel.log4cats.Logger



trait BusAlg[F[_]] {
  def subscribe[T](f: PartialFunction[AdminEvent, F[Option[T]]]): Stream[F, T]

  /**
   * Always do something when partial function matches
   */
  def subscribe_(f: PartialFunction[AdminEvent, F[Unit]]): Stream[F, Unit]

  def subscribe[A, T](preProcess: Pipe[F, AdminEvent, A])(f: A => F[Option[T]]): Stream[F, T]

  def publish(e: AdminEvent): F[Unit]
}

object BusAlg {
  def resource[F[_]: Temporal](
                                queueSize: Int
                              )(implicit F: Concurrent[F],
                                errorReporter: Logger[F]): Resource[F, BusAlg[F]] =
    Resource.make(Topic[F, AdminEvent])(_.close.void).map { topic =>
      new BusAlg[F] {
        def subscribe[T](
                       f: PartialFunction[AdminEvent, F[Option[T]]]
                     ): Stream[F, T] = {
          subscribe(identity(_))(f.lift.map(_.getOrElse(F.pure(none[T]))))
        }

        def subscribe[A, T](preProcess: Pipe[F, AdminEvent, A])(f: A => F[Option[T]]): Stream[F, T] =
          topic
            .subscribe(queueSize)
            .through(preProcess)
            .evalMap(
              f.map(_.handleErrorWith(t => {
                errorReporter.error(t)("failed to handle event").as(none)
              }))
            )
            .flattenOption

        def publish(e: AdminEvent): F[Unit] = topic.publish1(e).void

        def subscribe_(f: PartialFunction[AdminEvent, F[Unit]]): Stream[F, Unit] =
          subscribe(f.andThen(_.map(Option(_))))



      }
    }
}