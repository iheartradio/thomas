package com.iheart.thomas
package analysis

import breeze.stats.meanAndVariance.MeanAndVariance
import cats.UnorderedFoldable
import cats.kernel.{CommutativeMonoid, Monoid}
import cats.implicits._
import henkan.convert.Syntax._
sealed trait KPIStats

case class Conversions(
    converted: Long,
    total: Long)
    extends KPIStats {
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

case class PerUserSamplesSummary(
    mean: Double,
    variance: Double,
    count: Long)
    extends KPIStats

object PerUserSamplesSummary {
  def fromSamples(samples: PerUserSamples): PerUserSamplesSummary =
    samples.summary

  def apply(samples: PerUserSamples): PerUserSamplesSummary = fromSamples(samples)

  implicit val instances: CommutativeMonoid[PerUserSamplesSummary] =
    new CommutativeMonoid[PerUserSamplesSummary] {
      def empty: PerUserSamplesSummary = PerUserSamplesSummary(0d, 0d, 0L)

      def combine(
          x: PerUserSamplesSummary,
          y: PerUserSamplesSummary
        ): PerUserSamplesSummary =
        (x.to[MeanAndVariance]() + y.to[MeanAndVariance]())
          .to[PerUserSamplesSummary]()
    }
}

trait Aggregation[Event, KS <: KPIStats] {
  def apply[C[_]: UnorderedFoldable](events: C[Event]): KS
}

object Aggregation {
  implicit val conversionsAggregation: Aggregation[ConversionEvent, Conversions] =
    new Aggregation[ConversionEvent, Conversions] {
      def apply[C[_]: UnorderedFoldable](events: C[ConversionEvent]) =
        Conversions(events)
    }

  implicit val accumulativeAggregation
      : Aggregation[PerUserSamples, PerUserSamplesSummary] =
    new Aggregation[PerUserSamples, PerUserSamplesSummary] {
      def apply[C[_]: UnorderedFoldable](events: C[PerUserSamples]) =
        events.unorderedFold.summary
    }

}
