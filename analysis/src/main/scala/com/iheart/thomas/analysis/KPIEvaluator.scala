package com.iheart.thomas.analysis

import cats.Monad
import cats.data.NonEmptyList
import com.iheart.thomas.{ArmName, GroupName}
import com.stripe.rainier.sampler.{RNG, SamplerConfig}
import cats.implicits._
import com.stripe.rainier.core.Beta

trait KPIEvaluator[F[_], Model, Measurement] {
  def evaluate(
      model: Model,
      measurements: Map[ArmName, Measurement],
      benchmark: Option[(ArmName, Measurement)]
    ): F[List[Evaluation]]

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
    ): F[Samples[Diff]]

  def compare(
      model: Model,
      baseline: Measurement,
      results: Measurement
    ): F[Samples[Diff]] = compare((baseline, model), (results, model))
}

case class Evaluation(
    name: ArmName,
    probabilityBeingOptimal: Probability,
    resultAgainstBenchmark: Option[BenchmarkResult])

object KPIEvaluator {

  def apply[F[_], Model, Measurement](
      implicit inst: KPIEvaluator[F, Model, Measurement]
    ): KPIEvaluator[F, Model, Measurement] = inst

  implicit def betaBayesianInstance[F[_]: Monad](
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
      F: Monad[F])
      extends KPIEvaluator[F, Model, Measurement] {

    protected def sampleIndicator(
        k: Model,
        data: Measurement
      ): Indicator

    def evaluate(
        model: Model,
        measurements: Map[ArmName, Measurement],
        benchmarkO: Option[(ArmName, Measurement)]
      ): F[List[Evaluation]] =
      for {
        probabilities <- evaluate(measurements.mapValues((_, model)))
        r <-
          probabilities.toList
            .traverse {
              case (armName, probability) =>
                benchmarkO
                  .traverseFilter {
                    case (benchmarkName, benchmarkMeasurement)
                        if benchmarkName != armName =>
                      compare(
                        (benchmarkMeasurement, model),
                        (measurements.get(armName).get, model)
                      ).map(r => Option(BenchmarkResult(r, benchmarkName)))
                    case _ => none[BenchmarkResult].pure[F]
                  }
                  .map(brO => Evaluation(armName, probability, brO))
            }
      } yield r

    def compare(
        baseline: (Measurement, Model),
        results: (Measurement, Model)
      ): F[Samples[Diff]] = {
      val improvement =
        sampleIndicator(results._2, results._1)
          .map2(sampleIndicator(baseline._2, baseline._1))(_ - _)
          .predict()

      improvement.pure[F]
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
