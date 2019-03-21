package com.iheart.thomas
package analysis


import java.time.OffsetDateTime

import com.iheart.thomas.model.{Abtest, GroupName}
import com.stripe.rainier.sampler.RNG
import cats.implicits._

import scala.util.control.NoStackTrace
import cats.MonadError

trait AbtestKPI[F[_], K] {
  def assess(k: K,
             abtest: Abtest,
             baselineGroup: GroupName,
             start: Option[OffsetDateTime] = None,
             end: Option[OffsetDateTime] = None): F[Map[GroupName, NumericGroupResult]]
}


trait UpdatableKPI[F[_], K] {
  def updateFromData(kpi: K,
                     start: OffsetDateTime,
                     end: OffsetDateTime): F[(K, Double)]
}

object UpdatableKPI {
  def apply[F[_], K](implicit ev: UpdatableKPI[F, K]): UpdatableKPI[F, K] = ev
}

trait KPISyntax {

  implicit class abtestKPIOps[F[_], K](k: K)(implicit K: AbtestKPI[F, K]) {
    def assess(abtest: Abtest, baselineGroup: GroupName,
               start: Option[OffsetDateTime] = None,
               end: Option[OffsetDateTime] = None): F[Map[GroupName, NumericGroupResult]] =
      K.assess(k, abtest, baselineGroup, start, end)
  }

  implicit class updatableKPIOps[K](k: K) {
    def updateFromData[F[_]](start: OffsetDateTime,
                       end: OffsetDateTime)(implicit K: UpdatableKPI[F, K]): F[(K, Double)] =
      K.updateFromData(k, start, end)
  }
}

object AbtestKPI {
  def apply[F[_], K](implicit ev: AbtestKPI[F, K]): AbtestKPI[F, K] = ev

  case object ControlGroupMeasurementMissing extends RuntimeException with NoStackTrace

  abstract class BayesianAbtestKPI[F[_], K, M](implicit
                                            samplerSettings: SampleSettings,
                                            rng: RNG,
                                            K:  Measurable[F, M, K],
                                            F: MonadError[F, Throwable]
                                      ) extends AbtestKPI[F, K] {
    protected def sampleIndicator(k: K, data: M): Indicator

    def assess(k: K,
               abtest: Abtest,
               baselineGroup: GroupName,
               start: Option[OffsetDateTime] = None,
               end: Option[OffsetDateTime] = None
               ): F[Map[GroupName, NumericGroupResult]] = {

      for {
        allMeasurement <- K.measureAbtest(k, abtest, start, end)
        baselineMeasurements <- allMeasurement.get(baselineGroup).liftTo[F](BaselineGroupNameNotFound(baselineGroup, allMeasurement.keys))
      } yield {
        val groupMeasurements = allMeasurement.filterKeys(_ != baselineGroup)

        groupMeasurements.map {
          case (gn, ms) =>
            import samplerSettings._
            val improvement = (for {
              treatmentIndicator <- sampleIndicator(k, ms)
              controlIndicator <- sampleIndicator(k, baselineMeasurements)
            } yield treatmentIndicator - controlIndicator).sample(sampler, warmupIterations, iterations, keepEvery)

            (gn, NumericGroupResult(improvement))
        }
      }

    }
  }

  case class BaselineGroupNameNotFound(n: GroupName, groups: Iterable[GroupName]) extends RuntimeException with NoStackTrace {
    override def getMessage: String = s""" "$n" is not found in measurements: ${groups.mkString(",")}) """
  }


}
