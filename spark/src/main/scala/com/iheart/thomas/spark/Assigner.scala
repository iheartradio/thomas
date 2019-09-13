package com.iheart.thomas
package spark

import cats.Id
import cats.effect.{ContextShift, IO}
import com.iheart.thomas.abtest.AssignGroups
import com.iheart.thomas.abtest.model.{Abtest, Feature, UserGroupQuery}
import com.iheart.thomas.client.AbtestClient
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{udf, col}

class Assigner(data: Vector[(Abtest, Feature)]) extends Serializable {

  def assignUdf(feature: FeatureName) = udf { (userId: String) =>
    AssignGroups
      .assign[Id](data, UserGroupQuery(Some(userId), None, features = List(feature)))
      .get(feature)
      .map(_._1)
      .getOrElse(null)
  }

  def assignments(userIds: DataFrame,
                  feature: FeatureName,
                  idColumn: String): DataFrame = {

    import userIds.sparkSession.implicits._

    userIds.withColumn("assignment", assignUdf(feature)(col(idColumn)))
  }
}

object Assigner {
  def create(url: String): Assigner = create(url, None)
  def create(url: String, asOf: Long): Assigner = create(url, Some(asOf))

  def create(url: String, asOf: Option[Long]): Assigner = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val time = asOf.map(TimeUtil.toDateTime)
    implicit val csIo: ContextShift[IO] = IO.contextShift(global)
    val data = AbtestClient.testsWithFeatures[IO](url, time).unsafeRunSync()

    new Assigner(data)
  }
}
