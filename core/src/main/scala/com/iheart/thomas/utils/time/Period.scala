package com.iheart.thomas.utils.time

import cats.{Foldable, Reducible, Semigroup}
import cats.implicits._

import java.time.Instant

case class Period(from: Instant, to: Instant) {
  assert(
    !from.isAfter(to),
    s"Invalid Period($from, $to). The from and to is reverse."
  )
  def isBefore(instant: Instant): Boolean = to.isBefore(instant)
  def isAfter(instant: Instant): Boolean = from.isAfter(instant)
  def includes(instant: Instant): Boolean = !isBefore(instant) && !isAfter(instant)
  def extendsToInclude(instant: Instant): Period = {
    if (isBefore(instant)) Period(from, instant)
    else if (isAfter(instant)) Period(instant, to)
    else this
  }
}

object Period {
  implicit object instance extends Semigroup[Period] {
    def combine(x: Period, y: Period): Period = Period(
      first(x.from, y.from),
      last(x.to, y.to)
    )
  }

  def of(a: Instant, b: Instant): Period =
    if (a.isBefore(b)) Period(a, b) else Period(b, a)

  def fromStamps[F[_]: Reducible](stamps: F[Instant]): Period =
    stamps.reduceLeftTo(i => Period(i, i))(_.extendsToInclude(_))

  def of[F[_]: Foldable, A](
      withStamps: F[A],
      stamp: A => Instant
    ): Option[Period] =
    withStamps.reduceLeftToOption { a =>
      val s = stamp(a)
      Period(s, s)
    } { (p, a) =>
      p.extendsToInclude(stamp(a))
    }

}
