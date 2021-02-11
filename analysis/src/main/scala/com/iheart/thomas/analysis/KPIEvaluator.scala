package com.iheart.thomas.analysis

import cats.Applicative
import cats.data.NonEmptyList
import com.iheart.thomas.{ArmName, GroupName}
import com.stripe.rainier.sampler.{RNG, SamplerConfig}
import cats.implicits._
import com.stripe.rainier.core.Beta

trait KPIEvaluator[F[_], Model, Measurement] {
  def evaluate(
      model: Model,
      measurements: Map[ArmName, Measurement]
    ): F[Map[ArmName, Probability]] =
    evaluate(measurements.mapValues((_, model)))

  /**
    *
   * Measure optimal arm probability based on their model and measurement
    * @return
    */
  def evaluate(
      measurementsAndModel: Map[ArmName, (Measurement, Model)]
    ): F[Map[ArmName, Probability]]

  def compare(
      baseline: (Measurement, Model),
      results: (Measurement, Model)
    ): F[NumericGroupResult]

  def compare(
      model: Model,
      baseline: Measurement,
      results: Measurement
    ): F[NumericGroupResult] = compare((baseline, model), (results, model))
}

object KPIEvaluator {
  def apply[F[_], Model, Measurement](
      implicit inst: KPIEvaluator[F, Model, Measurement]
    ): KPIEvaluator[F, Model, Measurement] = inst

  implicit def betaBayesianInstance[F[_]: Applicative](
      implicit
      sampler: SamplerConfig,
      rng: RNG
    ): KPIEvaluator[F, BetaModel, Conversions] =
    new BayesianKPIEvaluator[F, BetaModel, Conversions] {
      protected def sampleIndicator(
          b: BetaModel,
          data: Conversions
        ) =
        BayesianKPIEvaluator.sample(b, data)
    }

  abstract class BayesianKPIEvaluator[F[_], Model, Measurement](
      implicit
      sampler: SamplerConfig,
      rng: RNG,
      F: Applicative[F])
      extends KPIEvaluator[F, Model, Measurement] {

    protected def sampleIndicator(
        k: Model,
        data: Measurement
      ): Indicator

    def compare(
        baseline: (Measurement, Model),
        results: (Measurement, Model)
      ): F[NumericGroupResult] = {
      val improvement =
        sampleIndicator(results._2, results._1)
          .map2(sampleIndicator(baseline._2, baseline._1))(_ - _)
          .predict()

      NumericGroupResult(improvement).pure[F]
    }

    def evaluate(
        allMeasurement: Map[GroupName, (Measurement, Model)]
      ): F[Map[GroupName, Probability]] =
      NonEmptyList
        .fromList(allMeasurement.toList)
        .map {
          _.nonEmptyTraverse {
            case (gn, (ms, k)) => sampleIndicator(k, ms).map((gn, _))
          }
        }
        .fold(F.pure(Map.empty[GroupName, Probability])) { rvGroupResults =>
          val numericGroupResult =
            rvGroupResults
              .map(_.toList.toMap)
              .predict()

          val initCounts = allMeasurement.map { case (gn, _) => (gn, 0L) }

          val winnerCounts = numericGroupResult.foldLeft(initCounts) {
            (counts, groupResult) =>
              val winnerGroup = groupResult.maxBy(_._2)._1
              counts.updated(winnerGroup, counts(winnerGroup) + 1)
          }

          val total = winnerCounts.toList.map(_._2).sum
          F.pure(winnerCounts.map {
            case (gn, c) => (gn, Probability(c.toDouble / total.toDouble))
          })
        }
  }

  object BayesianKPIEvaluator {
    def sample(
        model: BetaModel,
        data: Conversions
      ): Indicator = {
      val postAlpha = model.alphaPrior + data.converted
      val postBeta = model.betaPrior + data.total - data.converted
      Variable(Beta(postAlpha, postBeta).latent, None)
    }
  }
}
