package com.iheart.thomas
package analysis

import java.time.Instant

import com.iheart.thomas.abtest.model.Abtest
import com.stripe.rainier.sampler.{RNG, SamplerConfig}
import cats.implicits._

import scala.util.control.NoStackTrace
import cats.MonadError
import com.stripe.rainier.core.{Generator, ToGenerator}
trait AssessmentAlg[F[_], K] {
  def assess(
      k: K,
      abtest: Abtest,
      baselineGroup: GroupName,
      start: Option[Instant] = None,
      end: Option[Instant] = None
    ): F[Map[GroupName, BenchmarkResult]]

}

trait UpdatableKPI[F[_], K] {

  /**
    * replace the prior of the KPI with a new prior based on new data
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

  implicit class abtestKPIOps[F[_], K](k: K)(implicit K: AssessmentAlg[F, K]) {
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

object AssessmentAlg {
  def apply[F[_], K](implicit ev: AssessmentAlg[F, K]): AssessmentAlg[F, K] = ev

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
      extends AssessmentAlg[F, K] {
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

        groupMeasurements.map {
          case (gn, ms) =>
            val improvement =
              sampleIndicator(k, ms)
                .map2(sampleIndicator(k, baselineMeasurements))(_ - _)
                .predict()

            (gn, BenchmarkResult(improvement, baselineGroup))
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
