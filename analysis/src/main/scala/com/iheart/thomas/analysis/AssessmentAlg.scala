package com.iheart.thomas
package analysis

import java.time.Instant

import com.iheart.thomas.abtest.model.Abtest
import com.stripe.rainier.sampler.{RNG, Sampler}
import cats.implicits._

import scala.util.control.NoStackTrace
import cats.{Applicative, MonadError}
import cats.data.NonEmptyList
import com.stripe.rainier.core.{Generator, ToGenerator}
trait AssessmentAlg[F[_], K] {
  def assess(
      k: K,
      abtest: Abtest,
      baselineGroup: GroupName,
      start: Option[Instant] = None,
      end: Option[Instant] = None
    ): F[Map[GroupName, NumericGroupResult]]

}

trait BasicAssessmentAlg[F[_], K, M] {
  def assessOptimumGroup(
      k: K,
      measurements: Map[GroupName, M]
    ): F[Map[GroupName, Probability]] =
    assessOptimumGroup(measurements.mapValues((_, k)))

  def assessOptimumGroup(
      measurements: Map[GroupName, (M, K)]
    ): F[Map[GroupName, Probability]]
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
      ): F[Map[GroupName, NumericGroupResult]] =
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
    ): ToGenerator[(A, B), U] = new ToGenerator[(A, B), U] {
    def apply(p: (A, B)): Generator[U] = tga(p._1).zip(tgB(p._2)).map(_._2)
  }

  case object ControlGroupMeasurementMissing
      extends RuntimeException
      with NoStackTrace

  abstract class BayesianBasicAssessmentAlg[F[_], K, M](
      implicit
      sampler: Sampler,
      rng: RNG,
      F: Applicative[F])
      extends BasicAssessmentAlg[F, K, M] {

    protected def sampleIndicator(
        k: K,
        data: M
      ): Indicator

    def assessOptimumGroup(
        allMeasurement: Map[GroupName, (M, K)]
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

  abstract class BayesianAssessmentAlg[F[_], K, M](
      implicit
      sampler: Sampler,
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
      ): F[Map[GroupName, NumericGroupResult]] = {

      for {
        allMeasurement <- K.measureAbtest(k, abtest, start, end)
        baselineMeasurements <- allMeasurement
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

            (gn, NumericGroupResult(improvement))
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
