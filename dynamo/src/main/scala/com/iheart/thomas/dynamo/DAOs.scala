package com.iheart.thomas.dynamo

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.analysis.Conversions
import com.iheart.thomas.bandit.bayesian.BayesianState
import lihua.{EntityDAO, EntityId}
import lihua.dynamo.ScanamoEntityDAO
import DynamoFormats._
import cats.effect.Async

object DAOs {
  val banditStateTableName = "BanditState"
  implicit def stateDAO[F[_]: Async](implicit dynamoClient: AmazonDynamoDBAsync)
    : EntityDAO[F, BayesianState[Conversions], List[EntityId]] =
    new ScanamoEntityDAO[F, BayesianState[Conversions]](banditStateTableName,
                                                        dynamoClient)

}
