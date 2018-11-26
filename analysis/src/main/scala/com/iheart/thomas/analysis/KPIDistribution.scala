package com.iheart.thomas.analysis

import java.time.OffsetDateTime

import cats.{Functor, MonadError}
import com.iheart.thomas.Formats.j
import com.iheart.thomas.analysis.AbtestKPI.BayesianAbtestKPI
import com.iheart.thomas.analysis.DistributionSpec.Normal
import com.stripe.rainier.compute.Real
import com.stripe.rainier.core.{Continuous, Gamma, RandomVariable}
import com.stripe.rainier.sampler.RNG
import io.estatico.newtype.Coercible
import monocle.macros.syntax.lens._
import org.apache.commons.math3.distribution.GammaDistribution
import org.apache.commons.math3.stat.inference.KolmogorovSmirnovTest
import play.api.libs.json._
import cats.implicits._
import com.iheart.thomas.model.{Abtest, GroupName}
import implicits._

sealed trait KPIDistribution extends Serializable with Product {
  def name: KPIName
}

object KPIDistribution  {
  import julienrf.json.derived

  implicit private val normalDistFormat: Format[Normal] = j.format[Normal]

  implicit private def coercibleFormat[A, B](implicit ev: Coercible[Format[A], Format[B]],
  A: Format[A]): Format[B] = ev(A)

  implicit val mdFormat: Format[KPIDistribution] =
    derived.flat.oformat[KPIDistribution](( __ \ "type").format[String])

  implicit def abtestKPIGeneric[F[_]](
    implicit G: AbtestKPI[F, GammaKPIDistribution]
  ) : AbtestKPI[F, KPIDistribution] = new AbtestKPI[F, KPIDistribution] {
    def assess(k: KPIDistribution, abtest: Abtest, baselineGroup: GroupName): F[Map[GroupName, NumericGroupResult]] =
      k match {
        case g: GammaKPIDistribution => G.assess(g, abtest, baselineGroup)
      }
  }

  implicit def updatableKPIGeneric[F[_]: Functor](
                                 implicit G: UpdatableKPI[F, GammaKPIDistribution]
                                 ) : UpdatableKPI[F, KPIDistribution] = new UpdatableKPI[F, KPIDistribution] {
    def rescalePrior(k: KPIDistribution, scale: Double): KPIDistribution = k match {
      case g: GammaKPIDistribution => G.rescalePrior(g, scale)
    }

    def updateFromData(kpi: KPIDistribution, start: OffsetDateTime, end: OffsetDateTime): F[(KPIDistribution, Double)] =
      kpi match {
        case g: GammaKPIDistribution => G.updateFromData(g, start, end).widen
      }
  }

}


case class GammaKPIDistribution(name: KPIName,
                                shapePrior: Normal,
                                scalePrior: Normal) extends KPIDistribution {
  def scalePriors(by: Double): GammaKPIDistribution = {
    val g = this.lens(_.shapePrior.scale).modify(_ * by)
    g.lens(_.scalePrior.scale).modify(_ * by)
  }
}

object GammaKPIDistribution {

  implicit def gammaKPIMeasurable[F[_]](implicit
                                        sampleSettings: SampleSettings,
                                        rng: RNG,
                                        K:  Measurable[F, GammaKPIDistribution],
                                        F: MonadError[F, Throwable]) : AbtestKPI[F, GammaKPIDistribution] with UpdatableKPI[F, GammaKPIDistribution] =
    new BayesianAbtestKPI[F, GammaKPIDistribution] with UpdatableKPI[F, GammaKPIDistribution] {

      private def fitModel(gk: GammaKPIDistribution, data: List[Double]): RandomVariable[(Real, Real, Continuous)] =
        for {
          shape <- gk.shapePrior.distribution.param
          scale <- gk.scalePrior.distribution.param
          g <- Gamma(shape, scale).fit(data)
        } yield (shape, scale, g)

      def fitToData(gk: GammaKPIDistribution, data: List[Double]): Indicator =
        fitModel(gk, data).map {
          case (shape, scale, _) => shape * scale
        }

      def updateFromData(k: GammaKPIDistribution,
                         start: OffsetDateTime,
                         end: OffsetDateTime): F[(GammaKPIDistribution, Double)] =
        K.measureHistory(k, start, end).map { data =>
          val model = fitModel(k, data)

          val shapeSample = model.map(_._1).sample(sampleSettings)
          val scaleSample = model.map(_._2).sample(sampleSettings)

          val updated = k.copy(shapePrior = Normal.fit(shapeSample), scalePrior = Normal.fit(scaleSample))

          val ksTest = new KolmogorovSmirnovTest()
          val gd = new GammaDistribution(updated.shapePrior.location, updated.scalePrior.location)
          val ksStatistics = ksTest.kolmogorovSmirnovStatistic(gd, data.toArray)

          (updated, ksStatistics)
        }


      def rescalePrior(k: GammaKPIDistribution, scale: Double): GammaKPIDistribution =
        k.scalePriors(scale)
    }
}
