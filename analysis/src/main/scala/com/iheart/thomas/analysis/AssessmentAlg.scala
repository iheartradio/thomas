package com.iheart.thomas
package analysis

import java.time.OffsetDateTime

import com.iheart.thomas.abtest.model.Abtest
import com.stripe.rainier.sampler.RNG
import cats.implicits._

import scala.util.control.NoStackTrace
import cats.MonadError
import cats.effect.Sync
import com.stripe.rainier.cats.rainierMonadRandomVariable
import com.stripe.rainier.core.{Generator, ToGenerator}
trait AssessmentAlg[F[_], K] {
  def assess(k: K,
             abtest: Abtest,
             baselineGroup: GroupName,
             start: Option[OffsetDateTime] = None,
             end: Option[OffsetDateTime] = None): F[Map[GroupName, NumericGroupResult]]

}

trait BasicAssessmentAlg[F[_], K, M] {
  def assessOptimumGroup(k: K,
                         measurements: Map[GroupName, M]): F[Map[GroupName, Probability]]
}

trait UpdatableKPI[F[_], K] {

  /**
    * replace the prior of the KPI with a new prior based on new data
    * @param kpi
    */
  def updateFromData(kpi: K, start: OffsetDateTime, end: OffsetDateTime): F[(K, Double)]
}

object UpdatableKPI {
  def apply[F[_], K](implicit ev: UpdatableKPI[F, K]): UpdatableKPI[F, K] = ev
}

trait KPISyntax {

  implicit class abtestKPIOps[F[_], K](k: K)(implicit K: AssessmentAlg[F, K]) {
    def assess(
        abtest: Abtest,
        baselineGroup: GroupName,
        start: Option[OffsetDateTime] = None,
        end: Option[OffsetDateTime] = None): F[Map[GroupName, NumericGroupResult]] =
      K.assess(k, abtest, baselineGroup, start, end)
  }

  implicit class updatableKPIOps[K](k: K) {
    def updateFromData[F[_]](start: OffsetDateTime, end: OffsetDateTime)(
        implicit K: UpdatableKPI[F, K]): F[(K, Double)] =
      K.updateFromData(k, start, end)
  }
}

object AssessmentAlg {
  def apply[F[_], K](implicit ev: AssessmentAlg[F, K]): AssessmentAlg[F, K] = ev

  implicit val toGenerator: ToGenerator[GroupName, GroupName] =
    new ToGenerator[GroupName, GroupName] {
      override def apply(t: GroupName): Generator[GroupName] = Generator.constant(t)
    }

  case object ControlGroupMeasurementMissing extends RuntimeException with NoStackTrace

  abstract class BayesianBasicAssessmentAlg[F[_], K, M](implicit
                                                        samplerSettings: SampleSettings,
                                                        rng: RNG,
                                                        F: Sync[F])
      extends BasicAssessmentAlg[F, K, M] {

    protected def sampleIndicator(k: K, data: M): Indicator

    def assessOptimumGroup(
        k: K,
        allMeasurement: Map[GroupName, M]): F[Map[GroupName, Probability]] = F.delay {
      val rvGroupResults = allMeasurement.toList
        .traverse {
          case (gn, ms) => sampleIndicator(k, ms).map((gn, _))
        }
        .map(_.toSeq)

      import samplerSettings._
      val numericGroupResult =
        rvGroupResults
          .sample[Seq[(GroupName, Double)]](sampler,
                                            warmupIterations,
                                            iterations,
                                            keepEvery)
      val initCounts = allMeasurement.map { case (gn, _) => (gn, 0L) }

      val winnerCounts = numericGroupResult.foldLeft(initCounts) {
        (counts, groupResult) =>
          val winnerGroup = groupResult.maxBy(_._2)._1
          counts.updated(winnerGroup, counts(winnerGroup) + 1)
      }
      val total = winnerCounts.toList.map(_._2).sum

      winnerCounts.map {
        case (gn, c) => (gn, Probability(c.toDouble / total.toDouble))
      }
    }
  }

  abstract class BayesianAssessmentAlg[F[_], K, M](implicit
                                                   samplerSettings: SampleSettings,
                                                   rng: RNG,
                                                   K: Measurable[F, M, K],
                                                   F: MonadError[F, Throwable])
      extends AssessmentAlg[F, K] {
    protected def sampleIndicator(k: K, data: M): Indicator

    def assess(
        k: K,
        abtest: Abtest,
        baselineGroup: GroupName,
        start: Option[OffsetDateTime] = None,
        end: Option[OffsetDateTime] = None): F[Map[GroupName, NumericGroupResult]] = {

      for {
        allMeasurement <- K.measureAbtest(k, abtest, start, end)
        baselineMeasurements <- allMeasurement
          .get(baselineGroup)
          .liftTo[F](BaselineGroupNameNotFound(baselineGroup, allMeasurement.keys))
      } yield {
        val groupMeasurements = allMeasurement.filterKeys(_ != baselineGroup)

        groupMeasurements.map {
          case (gn, ms) =>
            import samplerSettings._
            val improvement = (for {
              treatmentIndicator <- sampleIndicator(k, ms)
              controlIndicator <- sampleIndicator(k, baselineMeasurements)
            } yield treatmentIndicator - controlIndicator)
              .sample(sampler, warmupIterations, iterations, keepEvery)

            (gn, NumericGroupResult(improvement))
        }
      }
    }

  }

  case class BaselineGroupNameNotFound(n: GroupName, groups: Iterable[GroupName])
      extends RuntimeException
      with NoStackTrace {
    override def getMessage: String =
      s""" "$n" is not found in measurements: ${groups.mkString(",")}) """
  }

}
