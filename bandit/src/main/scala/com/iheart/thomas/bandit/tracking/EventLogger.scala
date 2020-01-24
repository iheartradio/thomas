package com.iheart.thomas.bandit.tracking

import cats.Applicative
import cats.effect.Sync

import scala.annotation.implicitNotFound

@implicitNotFound(
  "Logger for tracking bandit operations is needed. Check `com.iheart.thomas.bandit.tracking.EventLogger` for options"
)
trait EventLogger[F[_]] {
  def apply(e: Event): F[Unit]

  def debug(m: => String): F[Unit]
}

object EventLogger {
  def noop[F[_]: Applicative]: EventLogger[F] = new EventLogger[F] {
    def apply(e: Event): F[Unit] = Applicative[F].unit
    def debug(s: => String): F[Unit] = Applicative[F].unit
  }

  def stdout[F[_]: Sync]: EventLogger[F] = new EventLogger[F] {
    def apply(e: Event): F[Unit] = Sync[F].delay(println(e))
    def debug(s: => String): F[Unit] = Sync[F].delay(println(s))

  }

}
