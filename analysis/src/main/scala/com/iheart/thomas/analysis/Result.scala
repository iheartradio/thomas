package com.iheart.thomas.analysis
import io.estatico.newtype.ops._


case class GroupResult(rawSample: List[Double]) {

  lazy val sorted = rawSample.sorted
  def findMinimum(threshold: Double): KPIDouble =
    KPIDouble(sorted.take((sorted.size.toDouble * (1.0 - threshold)).toInt).last)

  lazy val indicatorSample = rawSample.coerce[List[KPIDouble]]
  lazy val probabilityOfImprovement = Probability(rawSample.count(_ > 0).toDouble / rawSample.length)
  lazy val riskOfUsing = findMinimum(0.95)
  lazy val expectedEffect = KPIDouble(rawSample.sum / rawSample.size)
  lazy val medianEffect = findMinimum(0.5)
  lazy val riskOfNotUsing = KPIDouble(-findMinimum(0.05).d)
}

