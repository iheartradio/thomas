package com.iheart.thomas
package client

import java.time.Instant

import com.iheart.thomas.abtest.{AssignGroups, TestsData}
import cats.effect.{ConcurrentEffect, Resource, Timer}
import com.iheart.thomas.abtest.model.{GroupMeta, UserGroupQuery}
import mau.RefreshRef
import cats.implicits._

import scala.concurrent.duration.FiniteDuration

abstract class AutoRefreshGroupAssigner[F[_]] {
  def assign(query: UserGroupQuery): F[Map[FeatureName, AssignmentWithMeta]]
}

object AutoRefreshGroupAssigner {

  def resource[F[_]: Timer](
      abtestClient: AbtestClient[F],
      refreshPeriod: FiniteDuration,
      staleTimeout: FiniteDuration,
      testsRange: Option[FiniteDuration]
    )(implicit F: ConcurrentEffect[F],
      nowF: F[Instant]
    ): Resource[F, AutoRefreshGroupAssigner[F]] = {
    RefreshRef
      .resource[F, TestsData]((_: TestsData) => F.unit) //todo: possibly add logging here.
      .map { ref =>
        new AutoRefreshGroupAssigner[F] {
          def assign(
              query: UserGroupQuery
            ): F[Map[FeatureName, AssignmentWithMeta]] = {
            for {
              data <- ref
                .getOrFetch(refreshPeriod, staleTimeout)(
                  nowF.flatMap { now =>
                    abtestClient
                      .testsData(
                        testsRange.fold(now)(tr => now.minusNanos(tr.toNanos)),
                        testsRange
                      )
                  }
                )(PartialFunction.empty)

              assignment <- AssignGroups.assign[F](data, query, staleTimeout)
            } yield {
              assignment.map {
                case (fn, (gn, test)) =>
                  (fn, AssignmentWithMeta(gn, test.groupMetas.get(gn)))

              }
            }
          }
        }
      }
  }
}

case class AssignmentWithMeta(
    groupName: GroupName,
    meta: Option[GroupMeta])
