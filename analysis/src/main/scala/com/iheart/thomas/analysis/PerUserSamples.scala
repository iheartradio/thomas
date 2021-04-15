package com.iheart.thomas.analysis

case class PerUserSamples(
    mean: Double,
    count: Long,
    variance: Double) {
  lazy val countD: Double = count.toDouble
}
