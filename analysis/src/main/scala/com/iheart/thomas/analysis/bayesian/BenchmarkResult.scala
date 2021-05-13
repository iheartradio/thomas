package com.iheart.thomas.analysis.bayesian

import com.iheart.thomas.ArmName
import com.iheart.thomas.analysis.`package`.{Diff, Samples}
import com.iheart.thomas.analysis.{KPIDouble, Probability}
import io.estatico.newtype.ops._

case class BenchmarkResult(
    rawSample: Samples[Diff],
    benchmarkArm: ArmName) {

  lazy val sorted = rawSample.sorted
  def findMinimum(threshold: Diff): KPIDouble =
    KPIDouble(sorted.take((sorted.size.toDouble * (1.0 - threshold)).toInt).last)

  lazy val indicatorSample = rawSample.coerce[List[KPIDouble]]
  lazy val probabilityOfImprovement = Probability(
    rawSample.count(_ > 0).toDouble / rawSample.length
  )
  lazy val riskOfUsing = findMinimum(0.95)
  lazy val expectedEffect = KPIDouble(rawSample.sum / rawSample.size)
  lazy val medianEffect = findMinimum(0.5)
  lazy val riskOfNotUsing = KPIDouble(-findMinimum(0.05).d)

}
