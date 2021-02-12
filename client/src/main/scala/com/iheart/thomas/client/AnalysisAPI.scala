package com.iheart.thomas
package client

import java.time.Instant

import cats.MonadError
import com.iheart.thomas.analysis._
import analysis.implicits._
import cats.implicits._
import com.iheart.thomas.abtest.Error.NotFound

import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

trait AnalysisAPI[F[_], K <: KPIModel] {
  def updateKPI(
      name: KPIName,
      start: Instant,
      end: Instant
    ): F[(K, Double)]

  def saveKPI(kpi: K): F[K]

  def assess(
      feature: FeatureName,
      kpi: KPIName,
      baseline: GroupName,
      start: Option[Instant] = None,
      end: Option[Instant] = None
    ): F[Map[GroupName, BenchmarkResult]]

  def updateOrInitKPI(
      name: KPIName,
      start: Instant,
      end: Instant,
      init: => K
    )(implicit F: MonadError[F, Throwable]
    ): F[(K, Double)] = {
    updateKPI(name, start, end).recoverWith {
      case NotFound(_) => saveKPI(init).flatMap(k => updateKPI(k.name, start, end))
    }
  }
}

object AnalysisAPI {

  implicit def analysisAPIWithClient[F[_], K <: KPIModel](
      implicit UK: UpdatableKPI[F, K],
      abtestKPI: AssessmentAlg[F, K],
      client: AbtestClient[F],
      F: MonadError[F, Throwable],
      K: ClassTag[K]
    ) =
    new AnalysisAPI[F, K] {

      def validateKPIType(k: KPIModel): F[K] =
        k match {
          case K(mk) => mk.pure[F]
          case _     => F.raiseError(KPINotFound)
        }

      def updateKPI(
          name: KPIName,
          start: Instant,
          end: Instant
        ): F[(K, Double)] = {
        for {
          kpi <- client.getKPI(name.n).flatMap(validateKPIType)
          p <- kpi.updateFromData[F](start, end)
          (updated, score) = p
          stored <- client.saveKPI(updated).flatMap(validateKPIType)
        } yield (stored, score)
      }

      def assess(
          feature: FeatureName,
          kpi: KPIName,
          baseline: GroupName,
          start: Option[Instant] = None,
          end: Option[Instant] = None
        ): F[Map[GroupName, BenchmarkResult]] =
        for {
          kpi <- client.getKPI(kpi.n).flatMap(validateKPIType)
          abtestO <- client.test(feature, start)
          abtest <- abtestO.liftTo[F](AbtestNotFound)
          r <- kpi.assess(abtest.data, baseline, start, end)
        } yield r

      def saveKPI(kpi: K): F[K] = client.saveKPI(kpi).flatMap(validateKPIType)
    }

  case object AbtestNotFound extends RuntimeException with NoStackTrace
  case object KPINotFound extends RuntimeException with NoStackTrace
}
