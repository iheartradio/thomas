package com.iheart.thomas
package analysis


import java.time.OffsetDateTime

import com.iheart.thomas.model.{Abtest, GroupName}
import com.stripe.rainier.sampler.RNG
import cats.implicits._

import scala.util.control.NoStackTrace
import cats.MonadError

trait AbtestKPI[F[_], K] {
  def assess(k: K, abtest: Abtest, baselineGroup: GroupName): F[Map[GroupName, NumericGroupResult]]
}


trait UpdatableKPI[F[_], K] {
  def rescalePrior(k: K, scale: Double): K

  def updateFromData(kpi: K,
                     start: OffsetDateTime,
                     end: OffsetDateTime): F[(K, Double)]
}

object UpdatableKPI {
  def apply[F[_], K](implicit ev: UpdatableKPI[F, K]): UpdatableKPI[F, K] = ev
}

trait KPISyntax {

  implicit class abtestKPIOps[F[_], K](k: K)(implicit K: AbtestKPI[F, K]) {
    def assess(abtest: Abtest, baselineGroup: GroupName): F[Map[GroupName, NumericGroupResult]] = K.assess(k, abtest, baselineGroup)
  }

  implicit class updatableKPIOps[K](k: K) {
    def updateFromData[F[_]](start: OffsetDateTime,
                       end: OffsetDateTime)(implicit K: UpdatableKPI[F, K]): F[(K, Double)] =
      K.updateFromData(k, start, end)

    def rescalePrior[F[_]](scale: Double)(implicit K: UpdatableKPI[F, K]): K =
      K.rescalePrior(k, scale)
  }
}

object AbtestKPI {
  def apply[F[_], K](implicit ev: AbtestKPI[F, K]): AbtestKPI[F, K] = ev

  case object ControlGroupMeasurementMissing extends RuntimeException with NoStackTrace

  abstract class BayesianAbtestKPI[F[_], K](implicit
                                            samplerSettings: SampleSettings,
                                            rng: RNG,
                                            K:  Measurable[F, K],
                                            F: MonadError[F, Throwable]
                                      ) extends AbtestKPI[F, K] {
    protected def fitToData(k: K, data: Measurements): Indicator

    def assess(k: K,
               abtest: Abtest,
               baselineGroup: GroupName
               ): F[Map[GroupName, NumericGroupResult]] = {

      for {
        allMeasurement <- K.measureAbtest(k, abtest)
        baselineMeasurements <- allMeasurement.get(baselineGroup).liftTo[F](BaselineGroupNameNotFound(baselineGroup, abtest))
      } yield {
        val groupMeasurements = allMeasurement.filterKeys(_ != baselineGroup)

        groupMeasurements.map {
          case (gn, ms) =>
            import samplerSettings._
            val improvement = (for {
              treatmentIndicator <- fitToData(k, ms)
              controlIndicator <- fitToData(k, baselineMeasurements)
            } yield treatmentIndicator - controlIndicator).sample(sampler, warmupIterations, iterations, keepEvery)

            (gn, NumericGroupResult(improvement))
        }
      }

    }
  }

  case class BaselineGroupNameNotFound(n: GroupName, abtest: Abtest) extends RuntimeException with NoStackTrace {
    override def getMessage: String = s"$n is not a group in ${abtest.feature} test (${abtest.groups.map(_.name).mkString(",")})"
  }


}
