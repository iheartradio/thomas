package com.iheart.thomas.tracking

import cats.Applicative
import cats.effect.Sync

import scala.annotation.implicitNotFound
import org.typelevel.log4cats.Logger
@implicitNotFound(
  "Logger for tracking. Check `com.iheart.thomas.tracking.EventLogger` for options"
)
trait EventLogger[F[_]] {
  def apply(e: Event): F[Unit]

  def debug(m: => String): F[Unit]
}

object EventLogger {
  def noop[F[_]: Applicative]: EventLogger[F] =
    new EventLogger[F] {
      def apply(e: Event): F[Unit] = Applicative[F].unit
      def debug(s: => String): F[Unit] = Applicative[F].unit
    }

  def stdout[F[_]: Sync]: EventLogger[F] =
    new EventLogger[F] {
      def apply(e: Event): F[Unit] = Sync[F].delay(println(e))
      def debug(s: => String): F[Unit] = Sync[F].delay(println(s))
    }

  def catsLogger[F[_]](logger: Logger[F]): EventLogger[F] =
    new EventLogger[F] {
      def apply(e: Event): F[Unit] = logger.info(e.toString)
      def debug(s: => String): F[Unit] = logger.debug(s)
    }
}

trait Event extends Serializable with Product
