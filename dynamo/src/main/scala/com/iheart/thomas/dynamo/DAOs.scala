package com.iheart.thomas.dynamo

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.analysis.Conversions
import com.iheart.thomas.bandit.bayesian.BayesianState
import lihua.{EntityDAO, EntityId}
import lihua.dynamo.ScanamoEntityDAO
import DynamoFormats._
import cats.effect.Async
import com.iheart.thomas.bandit.BanditStateDAO

object DAOs {
  val banditStateTableName = "BanditState"

  def stateDAO[F[_]: Async](
      dynamoClient: AmazonDynamoDBAsync): BanditStateDAO[F, BayesianState[Conversions]] =
    BanditStateDAO.fromLihua(
      lihuaStateDAO(dynamoClient)
    )

  def lihuaStateDAO[F[_]: Async](dynamoClient: AmazonDynamoDBAsync)
    : EntityDAO[F, BayesianState[Conversions], List[EntityId]] =
    new ScanamoEntityDAO[F, BayesianState[Conversions]](banditStateTableName,
                                                        dynamoClient)

}
