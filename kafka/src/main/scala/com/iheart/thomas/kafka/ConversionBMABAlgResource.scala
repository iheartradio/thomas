package com.iheart.thomas
package kafka

import cats.NonEmptyParallel
import cats.effect._
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.analysis.Conversions
import com.iheart.thomas.bandit.bayesian.{BayesianMABAlgDepr, ConversionBMABAlg}
import com.iheart.thomas.dynamo.DynamoFormats._
import com.iheart.thomas.tracking.EventLogger
import com.typesafe.config.Config
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import dynamo.DynamoFormats._
import com.iheart.thomas.dynamo.AnalysisDAOs._

object ConversionBMABAlgResource {
  def apply[F[_]: Timer: EventLogger: NonEmptyParallel](
      implicit
      F: Concurrent[F],
      mongoDAOs: mongo.DAOs[F],
      amazonClient: DynamoDbAsyncClient
    ): Resource[F, ConversionBMABAlg[F]] = {
    implicit val stateDAO =
      dynamo.BanditsDAOs.banditState[F, Conversions]
    implicit val settingDAO =
      dynamo.BanditsDAOs.banditSettings[F]
    implicit val (abtestDAO, featureDAO) = mongoDAOs
    lazy val refreshPeriod =
      0.seconds //No cache is needed for abtests in Conversion API
    AbtestAlg.defaultResource[F](refreshPeriod).map { implicit abtestAlg =>
      BayesianMABAlgDepr[F, Conversions]
    }
  }

  def apply[F[_]: Timer: Concurrent: EventLogger: NonEmptyParallel](
      mongoConfig: Config
    )(implicit ex: ExecutionContext,
      amazonClient: DynamoDbAsyncClient
    ): Resource[F, ConversionBMABAlg[F]] =
    mongo.daosResource(mongoConfig).flatMap(implicit mongo => apply)

}
