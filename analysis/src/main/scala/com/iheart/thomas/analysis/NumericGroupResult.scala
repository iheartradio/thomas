package com.iheart.thomas.analysis
import io.estatico.newtype.ops._

case class NumericGroupResult(rawSample: List[Double]) {

  lazy val sorted = rawSample.sorted
  def findMinimum(threshold: Double): KPIDouble =
    KPIDouble(sorted.take((sorted.size.toDouble * (1.0 - threshold)).toInt).last)

  lazy val indicatorSample = rawSample.coerce[List[KPIDouble]]
  lazy val probabilityOfImprovement = Probability(
    rawSample.count(_ > 0).toDouble / rawSample.length
  )
  lazy val riskOfUsing = findMinimum(0.95)
  lazy val expectedEffect = KPIDouble(rawSample.sum / rawSample.size)
  lazy val medianEffect = findMinimum(0.5)
  lazy val riskOfNotUsing = KPIDouble(-findMinimum(0.05).d)

// todo: replace with new plotting
//  /**
//    * trace MCMC
//    */
//  def trace[F[_]](filePath: String)(implicit F: Sync[F]): F[Unit] = {
//    import com.cibo.evilplot.geometry.Extent
//    import com.stripe.rainier.plot.EvilTracePlot._
//    F.delay {
//      render(
//        traces(rawSample.map(d => Map("diff from control" -> d))),
//        filePath,
//        Extent(1800, 600)
//      )
//    }
//  }
//
//  def plot(plotPortionO: Option[Double] = None): String = {
//
//    val plotSample = plotPortionO.fold(rawSample) { pp =>
//      val noPlotEndPortion = (1d - pp) / 2d
//      val plotRangeMin = findMinimum(1d - noPlotEndPortion)
//      val plotRangeMax = findMinimum(noPlotEndPortion)
//      rawSample.filter(d => d > plotRangeMin && d < plotRangeMax)
//    }
//
//    DensityPlot().plot1D(plotSample).mkString("\n|")
//  }

}
