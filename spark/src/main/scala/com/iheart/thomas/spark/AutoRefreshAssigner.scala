package com.iheart.thomas
package spark

import cats.effect.{ContextShift, IO}
import mau.RefreshRef

import concurrent.duration._
import cats.implicits._
import org.apache.spark.sql.functions.udf

/** Provides a `udf` that assigns based on auto refreshed test data.
  *
  * @param url
  *   @param refreshPeriod how often test data is refreshed
  */
case class AutoRefreshAssigner(
    url: String,
    refreshPeriod: FiniteDuration = 10.minutes) {
  private lazy val inner: RefreshRef[IO, Assigner] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val csIo: ContextShift[IO] = IO.contextShift(global)
    implicit val timer = IO.timer(global)
    RefreshRef
      .create[IO, Assigner](_ => IO.unit)
      .flatTap(ref => ref.getOrFetch(refreshPeriod)(Assigner.create[IO](url, None)))

  }.unsafeRunSync()

  def assignUdf(feature: FeatureName) = udf { (userId: String) =>
    inner.get.unsafeRunSync().flatMap(_.assign(feature, userId))
  }
}
