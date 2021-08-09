package com.iheart.thomas.utils

import cats.Functor
import cats.effect.Timer

import java.time.format.DateTimeFormatter
import java.time._
import java.util.concurrent.TimeUnit

import cats.implicits._
import scala.util.Try
import scala.concurrent.duration.{Duration => CDuration, _}

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

  /** Stolen from Akka
    * https://github.com/akka/akka/blob/master/akka-actor/src/main/scala/akka/util/PrettyDuration.scala
    */
  implicit class PrettyPrintableDuration(val duration: CDuration) extends AnyVal {
    import java.util.Locale

    /** Selects most appropriate TimeUnit for given duration and formats it
      * accordingly, with 4 digits precision *
      */
    def pretty: String = pretty(includeNanos = false)

    /** Selects most appropriate TimeUnit for given duration and formats it
      * accordingly
      */
    def pretty(includeNanos: Boolean, precision: Int = 4): String = {
      require(precision > 0, "precision must be > 0")

      duration match {
        case d: FiniteDuration =>
          val nanos = d.toNanos
          val unit = chooseUnit(nanos)
          val value = nanos.toDouble / NANOSECONDS.convert(1, unit)

          s"%.${precision}g %s%s".formatLocal(
            Locale.ROOT,
            value,
            abbreviate(unit),
            if (includeNanos) s" ($nanos ns)" else ""
          )

        case CDuration.MinusInf => s"-∞ (minus infinity)"
        case CDuration.Inf      => s"∞ (infinity)"
        case _                  => "undefined"
      }
    }

    def chooseUnit(nanos: Long): TimeUnit = {
      val d = nanos.nanos

      if (d.toDays > 0) DAYS
      else if (d.toHours > 0) HOURS
      else if (d.toMinutes > 0) MINUTES
      else if (d.toSeconds > 0) SECONDS
      else if (d.toMillis > 0) MILLISECONDS
      else if (d.toMicros > 0) MICROSECONDS
      else NANOSECONDS
    }

    def abbreviate(unit: TimeUnit): String = unit match {
      case NANOSECONDS  => "ns"
      case MICROSECONDS => "μs"
      case MILLISECONDS => "ms"
      case SECONDS      => "s"
      case MINUTES      => "min"
      case HOURS        => "h"
      case DAYS         => "d"
    }
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
