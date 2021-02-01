package com.iheart.thomas
package analysis

import cats.kernel.Monoid

case class Conversions(
    converted: Long,
    total: Long) {
  assert(
    converted <= total,
    s"Cannot crate a conversions whose converted count $converted is more than total count $total"
  )
  def rate = converted.toDouble / total.toDouble

  def sampleSize: Long = total

  override def toString: String =
    s"Conversions(converted: $converted, total: $total, rate: ${(rate * 100).formatted("%.2f")}%)"
}

object Conversions {
  implicit val monoidInstance: Monoid[Conversions] = new Monoid[Conversions] {
    def empty: Conversions = Conversions(0, 0)

    def combine(
        x: Conversions,
        y: Conversions
      ): Conversions =
      Conversions(
        x.converted + y.converted,
        x.total + y.total
      )
  }
}
