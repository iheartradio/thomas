package com.iheart.thomas.analysis

import breeze.stats.meanAndVariance
import PerUserSamples.LnSummary
import cats.kernel.CommutativeMonoid
import henkan.convert.Syntax._

case class PerUserSamples(
    values: Array[Double]) {
  def map(f: Double => Double): PerUserSamples = PerUserSamples(values.map(f))

  def ln: PerUserSamples = map(Math.log)

  lazy val lnSummary: LnSummary = meanAndVariance(ln.values).to[LnSummary]()

}

object PerUserSamples {
  type LnSummary = PerUserSamplesLnSummary

  implicit val instances: CommutativeMonoid[PerUserSamples] =
    new CommutativeMonoid[PerUserSamples] {
      def empty: PerUserSamples = PerUserSamples(Array.empty)

      override def combine(
          x: PerUserSamples,
          y: PerUserSamples
        ): PerUserSamples = PerUserSamples(Array.concat(x.values, y.values))
    }
}
