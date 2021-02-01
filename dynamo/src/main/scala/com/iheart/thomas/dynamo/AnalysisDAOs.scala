package com.iheart.thomas.dynamo

import cats.effect.Async
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.analysis.{
  BetaModel,
  ConversionKPI,
  ConversionKPIDAO,
  KPIName
}
import com.iheart.thomas.dynamo.DynamoFormats._
import lihua.dynamo.ScanamoManagement
import org.scanamo.syntax._

object AnalysisDAOs extends ScanamoManagement {
  val conversionKPITableName = "ds-abtest-conversion-kpi"
  val conversionKPIKeyName = "name"
  val conversionKPIKey = ScanamoDAOHelperStringKey.keyOf(conversionKPIKeyName)

  def ensureAnalysisTables[F[_]: Async](
      readCapacity: Long = 2,
      writeCapacity: Long = 2
    )(implicit dc: AmazonDynamoDBAsync
    ): F[Unit] =
    ensureTable(
      dc,
      conversionKPITableName,
      Seq(conversionKPIKey),
      readCapacity,
      writeCapacity
    )

  implicit def conversionKPIDAO[F[_]: Async](
      implicit dynamoClient: AmazonDynamoDBAsync
    ): ConversionKPIDAO[F] =
    new ScanamoDAOHelperStringLikeKey[F, ConversionKPI, KPIName](
      conversionKPITableName,
      conversionKPIKeyName,
      dynamoClient
    ) with ConversionKPIDAO[F] {
      def updateModel(
          name: KPIName,
          model: BetaModel
        ): F[ConversionKPI] =
        toF(
          sc.exec(
            table.update(conversionKPIKeyName -> name.n, set("model" -> model))
          )
        )
    }

}
