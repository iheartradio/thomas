package com.iheart.thomas
package abtest


import java.time.Instant

import cats.effect.{Async, Resource}
import com.iheart.thomas.abtest.model._
import mau.RefreshRef
import cats.implicits._
import com.iheart.thomas.abtest.AssignGroups.AssignmentResult

import scala.concurrent.duration.FiniteDuration

abstract class PerformantAssigner[F[_]] {
  def assign(query: UserGroupQuery): F[Map[FeatureName, AssignmentResult]]
}

object PerformantAssigner {

  case class Config(
                     refreshPeriod: FiniteDuration,
                     staleTimeout: FiniteDuration,
                     testsRange: Option[FiniteDuration])

  def resource[F[_]: Async](
                             dataProvider: TestsDataProvider[F],
                             config: Config
                           )(implicit
                             nowF: F[Instant]
                           ): Resource[F, PerformantAssigner[F]] =
    resource[F](
      dataProvider,
      refreshPeriod = config.refreshPeriod,
      staleTimeout = config.staleTimeout,
      testsRange = config.testsRange
    )

  /** @param dataProvider
   *   client to get A/B tests data
   * @param refreshPeriod
   *   how ofter the data is refreshed
   * @param staleTimeout
   *   how stale is the data allowed to be (in cases when refresh fails)
   * @param testsRange
   *   time range during which valid tests are used to for getting assignment. if
   *   the `at` field in the UserGroupQuery is outside this range, the assignment
   *   will fail
   * @return
   *   A Resource of An [[PerformantAssigner]]
   */
  def resource[F[_]](
                      dataProvider: TestsDataProvider[F],
                      refreshPeriod: FiniteDuration,
                      staleTimeout: FiniteDuration,
                      testsRange: Option[FiniteDuration]
                    )(implicit F: Async[F],
                      nowF: F[Instant]
                    ): Resource[F, PerformantAssigner[F]] = {
    RefreshRef
      .resource[F, TestsData]((_: TestsData) =>
        F.unit
      ) //todo: possibly add logging here.
      .map { ref =>
        new PerformantAssigner[F] {
          def assign(
                      query: UserGroupQuery
                    ): F[Map[FeatureName, AssignmentResult]] = {
            for {
              data <- ref
                .getOrFetch(refreshPeriod, staleTimeout)(
                  nowF.flatMap { now =>
                    dataProvider
                      .getTestsData(
                        testsRange.fold(now)(tr => now.minusNanos(tr.toNanos)),
                        testsRange
                      )
                  }
                )(PartialFunction.empty)

              assignment <- AssignGroups.assign[F](data, query, staleTimeout)
            } yield assignment
          }
        }
      }
  }
}
