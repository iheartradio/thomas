package com.iheart.thomas.http4s
import java.time.LocalDateTime

import cats.effect.concurrent.Ref
import cats.effect.{ConcurrentEffect, Fiber, Resource, Timer}
import fs2.Stream
import cats.implicits._

import concurrent.duration._

trait BanditRunner[F[_]] {
  def stopIglooEventMonitor: F[Boolean]

  def startIglooEventMonitor: F[Boolean]

  def startPeriodicalReallocation: F[Boolean]

  def stopPeriodicalReallocation: F[Boolean]
}

object BanditRunner {
  implicit def default[F[_]: Timer](
      reallocationRepeatDuration: FiniteDuration
    )(implicit F: ConcurrentEffect[F]
    ): Resource[F, BanditRunner[F]] = ???
}
