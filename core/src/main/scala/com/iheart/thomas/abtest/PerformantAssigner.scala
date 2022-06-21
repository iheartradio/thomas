package com.iheart.thomas
package abtest

import cats.effect.{Async, Resource}
import com.iheart.thomas.abtest.model._
import mau.RefreshRef
import cats.implicits._
import com.iheart.thomas.abtest.AssignGroups.AssignmentResult
import com.typesafe.config.Config
import pureconfig.ConfigSource

import scala.concurrent.duration.FiniteDuration

abstract class PerformantAssigner[F[_]] {
  def assign(query: UserGroupQuery): F[Map[FeatureName, AssignmentResult]]
}

object PerformantAssigner {

  case class Settings(
      refreshPeriod: FiniteDuration,
      staleTimeout: FiniteDuration,
      testsRange: Option[FiniteDuration])

  def resource[F[_]: Async](
      implicit
      dataProvider: TestsDataProvider[F],
      cfg: Config
    ): Resource[F, PerformantAssigner[F]] = {
    import pureconfig.module.catseffect.syntax._
    import pureconfig.generic.auto._

    Resource
      .eval(
        ConfigSource
          .fromConfig(cfg)
          .at("thomas.stream.job.assigner")
          .loadF[F, Settings]()
      )
      .flatMap { settings =>
        resource[F](
          dataProvider,
          refreshPeriod = settings.refreshPeriod,
          staleTimeout = settings.staleTimeout,
          testsRange = settings.testsRange
        )
      }
  }

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
    )(implicit F: Async[F]
    ): Resource[F, PerformantAssigner[F]] = {
    RefreshRef
      .resource[F, TestsData]((_: TestsData) =>
        F.unit
      ) // todo: possibly add logging here.
      .map { ref =>
        new PerformantAssigner[F] {
          def assign(
              query: UserGroupQuery
            ): F[Map[FeatureName, AssignmentResult]] = {
            for {
              data <- ref
                .getOrFetch(refreshPeriod, staleTimeout)(
                  utils.time.now[F].flatMap { now =>
                    dataProvider
                      .getTestsData(
                        testsRange.fold(now)(tr => now.minusNanos(tr.toNanos)),
                        testsRange
                      )
                  }
                )(PartialFunction.empty)

              assignment <- {
                implicit val nowF = utils.time.now[F]
                AssignGroups.assign[F](data, query, staleTimeout)
              }
            } yield assignment
          }
        }
      }
  }
}
