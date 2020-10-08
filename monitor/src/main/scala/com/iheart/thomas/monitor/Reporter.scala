package com.iheart.thomas.monitor

import cats.effect.{Concurrent, Fiber}
import io.chrisdavenport.log4cats.Logger
import cats.implicits._

/**
  * Fire and forget reporter
  *
  * @tparam F
  */
trait Reporter[F[_]] {
  def report(event: Event): F[Fiber[F, Unit]]

}

object Reporter {
  def datadog[F[_]](
      client: DatadogClient[F],
      logger: Logger[F]
    )(implicit F: Concurrent[F]
    ): Reporter[F] =
    (event: Event) =>
      F.start {
        (event.status match {
          case Event.Status.error => logger.error(event.toString)
          case _                  => F.unit
        }) *>
          client.send(event)(
            e =>
              logger.error(
                s"Failed to report $event due to " +
                  e.toString
              )
          )
      }
}
