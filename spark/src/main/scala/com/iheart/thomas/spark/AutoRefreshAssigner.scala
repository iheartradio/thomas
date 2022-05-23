package com.iheart.thomas
package spark

import cats.effect.IO
import mau.RefreshRef
import cats.effect.unsafe.implicits.global
import org.apache.spark.sql.expressions.UserDefinedFunction

import concurrent.duration._
import org.apache.spark.sql.functions.udf

/** Provides a `udf` that assigns based on auto refreshed test data.
  *
  * @param url
  * @param refreshPeriod
  *   how often test data is refreshed
  */
case class AutoRefreshAssigner(
    url: String,
    refreshPeriod: FiniteDuration = 10.minutes) {
  private lazy val inner: RefreshRef[IO, Assigner] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    RefreshRef
      .create[IO, Assigner](_ => IO.unit)
      .flatTap(ref => ref.getOrFetch(refreshPeriod)(Assigner.create[IO](url, None)))

  }.unsafeRunSync()

  def assignUdf(feature: FeatureName): UserDefinedFunction = udf(assignFunction(feature))

  def assignFunction(feature: FeatureName): String => Option[GroupName] = (userId: String) => {
    inner.get.unsafeRunSync().flatMap(_.assign(feature, userId))
  }

}
