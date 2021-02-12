package com.thomas.plot

import cats.effect.Sync
import com.cibo.evilplot._
import com.cibo.evilplot.geometry.{Drawable, Text}
import com.cibo.evilplot.plot._
import com.cibo.evilplot.plot.aesthetics.DefaultTheme._
import com.iheart.thomas.GroupName
import com.iheart.thomas.analysis.BenchmarkResult
import scalaview.Utils._

object PlotUtils {

  implicit class drawableSyntax(private val drawable: Drawable) extends AnyVal {
    def display[F[_]](
        height: Int = 800,
        width: Int = 1000
      )(implicit F: Sync[F]
      ): F[Unit] = {
      val img = drawable.asBufferedImage
      F.delay {
        val d =
          scalaview.SwingImageViewer(biResize(img, newW = width, newH = height))
      }
    }
  }

  def plot(
      gr: BenchmarkResult,
      gn: GroupName,
      bins: Int = 50,
      filterOutlier: Option[Double] = None
    ): Drawable = {

    val text = s"""
                  | Group $gn:
                  |  Chance of better than control: ${gr.probabilityOfImprovement}
                  |  Expected Benefit: ${gr.expectedEffect}
                  |  Median Benefit: ${gr.medianEffect}
                  |  Risk of Using: ${gr.riskOfUsing}
                  |  Risk of Not Using: ${gr.riskOfNotUsing}
                  |  Sample size: ${gr.rawSample.size}
               """.stripMargin
      .split("\\n")
      .map(Text(_, size = 12): Drawable)
      .reduce(_.above(_))

    val data =
      filterOutlier.fold(gr.rawSample) { fo =>
        assert(fo < 1)
        val (plotRangeMin, plotRangeMax) =
          (gr.findMinimum(fo), gr.findMinimum(1d - fo))
        gr.rawSample.filter(k => k > plotRangeMin.d && k < plotRangeMax.d)
      }

    val histo = Histogram(data, bins = bins)
      .title(s"Group $gn")
      .standard()
      .render()

    text.above(histo)
  }
}
