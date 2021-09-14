package com.iheart.thomas
package spark


import java.time.Instant
import cats.effect.{Async, IO}
import com.iheart.thomas.abtest.{AssignGroups, TestsData}
import com.iheart.thomas.abtest.model.UserGroupQuery
import com.iheart.thomas.client.AbtestClient
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{col, udf}
import cats.implicits._
import com.iheart.thomas.abtest.AssignGroups.AssignmentResult

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

class Assigner(data: TestsData) extends Serializable {

  def assignUdf(feature: FeatureName) =
    udf { (userId: String) =>
      assign(feature, userId).getOrElse(null)
    }

  def assign(
      feature: FeatureName,
      userId: String
    ): Option[GroupName] = {
    implicit val nowF = IO.delay(Instant.now)
    import cats.effect.unsafe.implicits.global
    AssignGroups
      .assign[IO](
        data,
        UserGroupQuery(Some(userId), None, features = List(feature)),
        Duration.Zero
      )
      .unsafeRunSync
      .get(feature)
      .collect { case AssignmentResult(groupName, _) =>
        groupName
      }
  }

  def assignments(
      userIds: DataFrame,
      feature: FeatureName,
      idColumn: String
    ): DataFrame = {
    userIds.withColumn("assignment", assignUdf(feature)(col(idColumn)))
  }
}

object Assigner {
  def create(url: String): Assigner = create(url, None)
  def create(
      url: String,
      asOf: Long
    ): Assigner = create(url, Some(asOf))

  def create(
      url: String,
      asOf: Option[Long]
    ): Assigner = apply(url, asOf)

  def apply(
      url: String,
      asOf: Option[Long]
    ): Assigner = {
    import cats.effect.unsafe.implicits.global
    implicit val ex: ExecutionContext = global.compute

    create[IO](url, asOf).unsafeRunSync()
  }

  def create[F[_]: Async](
      url: String,
      asOf: Option[Long]
    )(implicit ec: ExecutionContext
    ): F[Assigner] = {
    val time = asOf.map(Instant.ofEpochSecond).getOrElse(Instant.now)

    AbtestClient.testsData[F](url, time).map {
      new Assigner(_)
    }

  }
}
