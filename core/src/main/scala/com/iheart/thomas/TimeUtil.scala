/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas

import java.time._
import java.time.format.DateTimeFormatter

import scala.concurrent.duration.{FiniteDuration, NANOSECONDS}
import scala.util.Try
object TimeUtil {

  def defaultOffset: ZoneOffset =
    ZoneId.systemDefault().getRules.getOffset(Instant.now())

  def toDateTime(epochSecond: Long): OffsetDateTime =
    OffsetDateTime.ofInstant(
      Instant.ofEpochSecond(epochSecond),
      ZoneId.systemDefault()
    )

  implicit class InstantOps(private val me: Instant) extends AnyVal {
    def toODT = me.atOffset(ZoneOffset.UTC)

    def durationTo(that: Instant): FiniteDuration =
      FiniteDuration(Duration.between(me, that).toNanos, NANOSECONDS)

    def plusDuration(duration: FiniteDuration): Instant =
      me.plusNanos(duration.toNanos)

  }

  def parse(value: String): Option[OffsetDateTime] =
    Try(
      ZonedDateTime
        .parse(value, DateTimeFormatter.ISO_ZONED_DATE_TIME)
        .toOffsetDateTime
    ).toOption orElse
      Try(OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)).toOption orElse
      Try(OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME)).toOption orElse
      List(
        DateTimeFormatter.ISO_DATE_TIME,
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("M/d/yyyy H:m")
      ).collectFirst(Function.unlift { (tf: DateTimeFormatter) =>
          Try(LocalDateTime.parse(value, tf)).toOption
        })
        .orElse(
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
        )
        .map(_.atOffset(defaultOffset))

  def currentMinute: OffsetDateTime = {
    val now = OffsetDateTime.now
    OffsetDateTime.of(
      now.getYear,
      now.getMonthValue,
      now.getDayOfMonth,
      now.getHour,
      now.getMinute,
      0,
      0,
      TimeUtil.defaultOffset
    )
  }
}
