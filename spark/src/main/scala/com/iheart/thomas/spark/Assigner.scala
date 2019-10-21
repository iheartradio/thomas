package com.iheart.thomas
package spark

import cats.Id
import cats.effect.{ConcurrentEffect, ContextShift, IO}
import com.iheart.thomas.abtest.AssignGroups
import com.iheart.thomas.abtest.model.{Abtest, Feature, UserGroupQuery}
import com.iheart.thomas.client.AbtestClient
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{col, udf}
import cats.implicits._
import scala.concurrent.ExecutionContext

class Assigner(data: Vector[(Abtest, Feature)]) extends Serializable {

  def assignUdf(feature: FeatureName) = udf { (userId: String) =>
    assign(feature, userId).getOrElse(null)
  }

  def assign(
      feature: FeatureName,
      userId: String
    ): Option[GroupName] =
    AssignGroups
      .assign[Id](data, UserGroupQuery(Some(userId), None, features = List(feature)))
      .get(feature)
      .map(_._1)

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

    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val csIo: ContextShift[IO] = IO.contextShift(global)
    create[IO](url, asOf).unsafeRunSync()
  }

  def create[F[_]: ConcurrentEffect](
      url: String,
      asOf: Option[Long]
    )(implicit ec: ExecutionContext
    ): F[Assigner] = {
    val time = asOf.map(TimeUtil.toDateTime)

    AbtestClient.testsWithFeatures[F](url, time).map {
      new Assigner(_)
    }

  }
}
