package com.iheart.thomas.utils

import cats.Functor
import cats.effect.Timer

import java.time.format.DateTimeFormatter
import java.time._
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{FiniteDuration, NANOSECONDS}
import cats.implicits._
import scala.util.Try

package object time {

  def toDateTime(epochSecond: Long): OffsetDateTime =
    OffsetDateTime.ofInstant(
      Instant.ofEpochSecond(epochSecond),
      ZoneId.systemDefault()
    )

  def first(a: Instant, b: Instant): Instant =
    if (a.isBefore(b)) a else b

  def last(a: Instant, b: Instant): Instant =
    if (a.isAfter(b)) a else b

  implicit class InstantOps(private val me: Instant) extends AnyVal {
    def toOffsetDateTimeUTC = me.atOffset(ZoneOffset.UTC)

    def toOffsetDateTimeSystemDefault =
      me.atZone(ZoneId.systemDefault()).toOffsetDateTime

    def durationTo(that: Instant): FiniteDuration =
      FiniteDuration(Duration.between(me, that).toNanos, NANOSECONDS)

    def plusDuration(duration: FiniteDuration): Instant =
      me.plusNanos(duration.toNanos)

    /** Whether the instant has passed according to the Timer
      */
    def passed[F[_]: Timer: Functor]: F[Boolean] =
      now[F].map(_.isAfter(me))

  }

  def epochDay: Instant =
    LocalDate.of(1970, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)

  def parse(
      value: String,
      defaultOffset: ZoneOffset
    ): Option[OffsetDateTime] =
    Try(
      ZonedDateTime
        .parse(value, DateTimeFormatter.ISO_ZONED_DATE_TIME)
        .toOffsetDateTime
    ).toOption orElse
      Try(
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
      ).toOption orElse
      Try(
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME)
      ).toOption orElse
      List(
        DateTimeFormatter.ISO_DATE_TIME,
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("M/d/yyyy H:m")
      ).collectFirst(Function.unlift { (tf: DateTimeFormatter) =>
        Try(LocalDateTime.parse(value, tf)).toOption
      }).orElse(
        List(
          DateTimeFormatter.ISO_OFFSET_DATE,
          DateTimeFormatter.ISO_DATE,
          DateTimeFormatter.ISO_LOCAL_DATE,
          DateTimeFormatter.ISO_ORDINAL_DATE,
          DateTimeFormatter.ofPattern("M/d/yyyy"),
          DateTimeFormatter.ofPattern("yyyy/M/d")
        ).collectFirst(Function.unlift { (tf: DateTimeFormatter) =>
          Try(LocalDate.parse(value, tf).atStartOfDay()).toOption
        })
      ).map(_.atOffset(defaultOffset))

  def now[F[_]: Functor](implicit T: Timer[F]): F[Instant] =
    T.clock.realTime(TimeUnit.MILLISECONDS).map(Instant.ofEpochMilli)

}
