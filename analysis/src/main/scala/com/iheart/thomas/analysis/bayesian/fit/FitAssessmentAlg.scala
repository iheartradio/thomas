package com.iheart.thomas.analysis.bayesian.fit

import cats.MonadError
import cats.implicits._
import com.iheart.thomas.GroupName
import com.iheart.thomas.abtest.model.Abtest
import com.iheart.thomas.analysis.`package`.Indicator
import com.iheart.thomas.analysis.bayesian
import com.iheart.thomas.analysis.bayesian.BenchmarkResult
import com.stripe.rainier.core.{Generator, ToGenerator}
import com.stripe.rainier.sampler.{RNG, SamplerConfig}

import java.time.Instant
import scala.util.control.NoStackTrace

trait FitAssessmentAlg[F[_], K] {
  def assess(
      k: K,
      abtest: Abtest,
      baselineGroup: GroupName,
      start: Option[Instant] = None,
      end: Option[Instant] = None
    ): F[Map[GroupName, BenchmarkResult]]

}

trait UpdatableKPI[F[_], K] {

  /** replace the prior of the KPI with a new prior based on new data
    * @param kpi
    */
  def updateFromData(
      kpi: K,
      start: Instant,
      end: Instant
    ): F[(K, Double)]
}

object UpdatableKPI {
  def apply[F[_], K](implicit ev: UpdatableKPI[F, K]): UpdatableKPI[F, K] = ev
}

trait KPISyntax {

  implicit class abtestKPIOps[F[_], K](k: K)(implicit K: FitAssessmentAlg[F, K]) {
    def assess(
        abtest: Abtest,
        baselineGroup: GroupName,
        start: Option[Instant] = None,
        end: Option[Instant] = None
      ): F[Map[GroupName, BenchmarkResult]] =
      K.assess(k, abtest, baselineGroup, start, end)
  }

  implicit class updatableKPIOps[K](k: K) {
    def updateFromData[F[_]](
        start: Instant,
        end: Instant
      )(implicit K: UpdatableKPI[F, K]
      ): F[(K, Double)] =
      K.updateFromData(k, start, end)
  }
}

object FitAssessmentAlg {
  def apply[F[_], K](implicit ev: FitAssessmentAlg[F, K]): FitAssessmentAlg[F, K] =
    ev

  implicit def toGeneratorTuple[A, B, U](
      implicit tga: ToGenerator[A, A],
      tgB: ToGenerator[B, U]
    ): ToGenerator[(A, B), U] =
    new ToGenerator[(A, B), U] {
      def apply(p: (A, B)): Generator[U] = tga(p._1).zip(tgB(p._2)).map(_._2)
    }

  case object ControlGroupMeasurementMissing
      extends RuntimeException
      with NoStackTrace

  abstract class BayesianAssessmentAlg[F[_], K, M](
      implicit
      sampler: SamplerConfig,
      rng: RNG,
      K: Measurable[F, M, K],
      F: MonadError[F, Throwable])
      extends FitAssessmentAlg[F, K] {
    protected def sampleIndicator(
        k: K,
        data: M
      ): Indicator

    def assess(
        k: K,
        abtest: Abtest,
        baselineGroup: GroupName,
        start: Option[Instant] = None,
        end: Option[Instant] = None
      ): F[Map[GroupName, BenchmarkResult]] = {

      for {
        allMeasurement <- K.measureAbtest(k, abtest, start, end)
        baselineMeasurements <-
          allMeasurement
            .get(baselineGroup)
            .liftTo[F](BaselineGroupNameNotFound(baselineGroup, allMeasurement.keys))
      } yield {
        val groupMeasurements = allMeasurement.filterKeys(_ != baselineGroup)

        groupMeasurements.map { case (gn, ms) =>
          val improvement =
            sampleIndicator(k, ms)
              .map2(sampleIndicator(k, baselineMeasurements))(_ - _)
              .predict()

          (gn, bayesian.BenchmarkResult(improvement, baselineGroup))
        }
      }
    }

  }

  case class BaselineGroupNameNotFound(
      n: GroupName,
      groups: Iterable[GroupName])
      extends RuntimeException
      with NoStackTrace {
    override def getMessage: String =
      s""" "$n" is not found in measurements: ${groups.mkString(",")}) """
  }

}
