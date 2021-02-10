package com.iheart.thomas
package analysis

import cats.UnorderedFoldable
import cats.kernel.Monoid
import cats.implicits._

case class Conversions(
    converted: Long,
    total: Long) {
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

  def apply[C[_]: UnorderedFoldable](
      events: C[ConversionEvent]
    ): Conversions = {
    val converted = events.count(identity)
    val init = events.size - converted
    Conversions(converted, init)
  }
}
