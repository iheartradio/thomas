package com.iheart.thomas.monitor

import cats.effect.{Concurrent, Fiber}
import org.typelevel.log4cats.Logger
import cats.implicits._

/** Fire and forget reporter
  *
  * @tparam F
  */
trait Reporter[F[_]] {
  def report(event: MonitorEvent): F[Fiber[F, Throwable, Unit]]

}

object Reporter {
  def datadog[F[_]](
      client: DatadogClient[F],
      logger: Logger[F]
    )(implicit F: Concurrent[F]
    ): Reporter[F] =
    (event: MonitorEvent) =>
      F.start {
        (event.status match {
          case MonitorEvent.Status.error => logger.error(event.toString)
          case _                         => F.unit
        }) *>
          client.send(event)(e =>
            logger.error(
              s"Failed to report $event due to " +
                e.toString
            )
          )
      }
}
