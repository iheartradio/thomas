package com.iheart.thomas.analysis

import breeze.stats.meanAndVariance
import PerUserSamples.Summary
import henkan.convert.Syntax._

case class PerUserSamples(
    values: Array[Double]) {
  def map(f: Double => Double): PerUserSamples = PerUserSamples(values.map(f))

  def ln: PerUserSamples = map(Math.log)

  lazy val summary: Summary = meanAndVariance(values).to[Summary]()

}

object PerUserSamples {
  type Summary = PerUserSampleSummary
}
