package com.iheart.thomas.bandit.tracking

import cats.Applicative
import cats.effect.Sync

import scala.annotation.implicitNotFound

@implicitNotFound(
  "Logger for tracking bandit operations is needed. Check `com.iheart.thomas.bandit.tracking.EventLogger` for options"
)
trait EventLogger[F[_]] {
  def apply(e: Event): F[Unit]
}

object EventLogger {
  def noop[F[_]: Applicative]: EventLogger[F] =
    (e: Event) => Applicative[F].unit

  def stdout[F[_]: Sync]: EventLogger[F] =
    (e: Event) => Sync[F].delay(println(e))

}
