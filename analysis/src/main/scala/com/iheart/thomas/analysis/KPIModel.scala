package com.iheart.thomas
package analysis

import java.time.Instant
import cats.MonadError
import com.iheart.thomas.abtest.json.play.Formats.j
import com.iheart.thomas.analysis.AssessmentAlg.BayesianAssessmentAlg
import com.iheart.thomas.analysis.DistributionSpec.{Normal, Uniform}
import com.stripe.rainier.compute.Real
import com.stripe.rainier.core._
import com.stripe.rainier.sampler.{RNG, Sampler}
import io.estatico.newtype.Coercible
import org.apache.commons.math3.stat.inference.KolmogorovSmirnovTest
import _root_.play.api.libs.json._
import cats.effect.Sync
import cats.implicits._
import com.iheart.thomas.analysis.KPIEvaluation.BayesianKPIEvaluation

sealed trait KPIModel extends Serializable with Product {
  def name: KPIName
}

object KPIModel {
  import julienrf.json.derived

  implicit private val normalDistFormat: Format[Normal] = j.format[Normal]
  implicit private val uniformDistFormat: Format[Uniform] = j.format[Uniform]

  implicit private def coercibleFormat[A, B](
      implicit ev: Coercible[Format[A], Format[B]],
      A: Format[A]
    ): Format[B] = ev(A)

  implicit val mdFormat: Format[KPIModel] =
    derived.flat.oformat[KPIModel]((__ \ "type").format[String])

  implicit val bkpidFormat: Format[BetaKPIModel] =
    j.format[BetaKPIModel]
}

//todo: to be retired
case class BetaKPIModel(
    name: KPIName,
    alphaPrior: Double,
    betaPrior: Double)
    extends KPIModel {
  def updateFrom(conversions: Conversions): BetaKPIModel =
    copy(
      alphaPrior = conversions.converted + 1d,
      betaPrior = conversions.total - conversions.converted + 1d
    )
}

object BetaKPIModel {

  def sample(
      b: BetaKPIModel,
      data: Conversions
    ): Indicator = {
    val postAlpha = b.alphaPrior + data.converted
    val postBeta = b.betaPrior + data.total - data.converted
    Variable(Beta(postAlpha, postBeta).latent, None)
  }

  implicit def betaInstances[F[_]](
      implicit
      sampler: Sampler,
      rng: RNG,
      B: Measurable[F, Conversions, BetaKPIModel],
      F: MonadError[F, Throwable]
    ): AssessmentAlg[F, BetaKPIModel] with UpdatableKPI[F, BetaKPIModel] =
    new BayesianAssessmentAlg[F, BetaKPIModel, Conversions]
      with UpdatableKPI[F, BetaKPIModel] {

      protected def sampleIndicator(
          b: BetaKPIModel,
          data: Conversions
        ) =
        sample(b, data)

      def updateFromData(
          kpi: BetaKPIModel,
          start: Instant,
          end: Instant
        ): F[(BetaKPIModel, Double)] =
        B.measureHistory(kpi, start, end).map { conversions =>
          (
            kpi.updateFrom(conversions),
            0d
          )
        }
    }

  implicit def basicAssessmentAlg[F[_]](
      implicit
      sampler: Sampler,
      rng: RNG,
      F: Sync[F]
    ): KPIEvaluation[F, BetaKPIModel, Conversions] =
    new BayesianKPIEvaluation[F, BetaKPIModel, Conversions] {
      protected def sampleIndicator(
          b: BetaKPIModel,
          data: Conversions
        ) =
        sample(b, data)
    }
}

/**
  * https://stats.stackexchange.com/questions/30369/priors-for-log-normal-models
  * @param name
  * @param locationPrior
  * @param scaleLnPrior
  */
case class LogNormalKPIModel(
    name: KPIName,
    locationPrior: Normal,
    scaleLnPrior: Uniform)
    extends KPIModel

object LogNormalKPIModel {

  implicit def logNormalInstances[F[_]](
      implicit
      sampler: Sampler = Sampler.default,
      rng: RNG = RNG.default,
      K: Measurable[F, Measurements, LogNormalKPIModel],
      F: MonadError[F, Throwable]
    ): AssessmentAlg[F, LogNormalKPIModel] with UpdatableKPI[F, LogNormalKPIModel] =
    new BayesianAssessmentAlg[F, LogNormalKPIModel, Measurements]
      with UpdatableKPI[F, LogNormalKPIModel] {

      private def fitModel(
          logNormal: LogNormalKPIModel,
          data: List[Double]
        ): Variable[(Real, Real)] = {
        val location = logNormal.locationPrior.distribution.latent
        val scale = logNormal.scaleLnPrior.distribution.latent.exp
        val g = Model.observe(data, LogNormal(location, scale))
        Variable((location, scale), g)
      }

      def sampleIndicator(
          logNormalDistribution: LogNormalKPIModel,
          data: List[Double]
        ): Indicator = {
        fitModel(logNormalDistribution, data).map {
          case (location, scale) => (location + scale.pow(2d) / 2d).exp
        }
      }

      def updateFromData(
          k: LogNormalKPIModel,
          start: Instant,
          end: Instant
        ): F[(LogNormalKPIModel, Double)] =
        K.measureHistory(k, start, end).map { data =>
          val model = fitModel(k, data)

          val (locationSample, scaleSample) =
            model.predict().foldLeft((List.empty[Double], List.empty[Double])) {
              (memo, p) =>
                (p._1 :: memo._1, p._2 :: memo._2)
            }

          val updated = k.copy(
            locationPrior = Normal.fit(locationSample),
            scaleLnPrior = Uniform(
              Math.log(
                scaleSample.minimumOption.map(_ / 10).getOrElse(k.scaleLnPrior.from)
              ),
              Math.log(
                scaleSample.maximumOption.map(_ * 2).getOrElse(k.scaleLnPrior.to)
              )
            )
          )

          val ksTest = new KolmogorovSmirnovTest()
          val gd = new org.apache.commons.math3.distribution.LogNormalDistribution(
            updated.locationPrior.location,
            scaleSample.sum / scaleSample.size.toDouble
          )
          val ksStatistics = ksTest.kolmogorovSmirnovStatistic(gd, data.toArray)

          (updated, ksStatistics)
        }
    }
}
