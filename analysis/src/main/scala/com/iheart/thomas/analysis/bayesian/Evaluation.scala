package com.iheart.thomas
package analysis
package bayesian

case class Evaluation(
    name: ArmName,
    probabilityBeingOptimal: Probability,
    resultAgainstBenchmark: Option[BenchmarkResult])
