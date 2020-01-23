package com.iheart.thomas.abtest

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

trait DataProvider[F[_]] {
  def getTestsData(
      at: Instant,
      duration: Option[FiniteDuration]
    ): F[TestsData]
}
