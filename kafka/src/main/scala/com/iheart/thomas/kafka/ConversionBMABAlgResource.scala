package com.iheart.thomas
package kafka

import java.time.Instant
import cats.NonEmptyParallel
import cats.effect._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.analysis.Conversions
import com.iheart.thomas.bandit.bayesian.{
  BanditSettings,
  BayesianMABAlg,
  ConversionBMABAlg
}
import com.iheart.thomas.tracking.EventLogger
import com.iheart.thomas.{dynamo, mongo}
import com.stripe.rainier.sampler.{RNG, SamplerConfig}
import com.typesafe.config.Config

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import dynamo.DynamoFormats._

object ConversionBMABAlgResource {
  def apply[F[_]: Timer: EventLogger: NonEmptyParallel](
      implicit ex: ExecutionContext,
      F: Concurrent[F],
      mongoDAOs: mongo.DAOs[F],
      amazonClient: DynamoDbAsyncClient
    ): Resource[F, ConversionBMABAlg[F]] = {
    import mongo.idSelector
    implicit val stateDAO =
      dynamo.BanditsDAOs.banditState[F, Conversions]
    implicit val settingDAO =
      dynamo.BanditsDAOs.banditSettings[F, BanditSettings.Conversion]
    implicit val (abtestDAO, featureDAO) = mongoDAOs
    lazy val refreshPeriod =
      0.seconds //No cache is needed for abtests in Conversion API

    import dynamo.AnalysisDAOs._
    AbtestAlg.defaultResource[F](refreshPeriod).map { implicit abtestAlg =>
      implicit val ss = SamplerConfig.default
      implicit val rng = RNG.default
      implicit val nowF = F.delay(Instant.now)
      BayesianMABAlg[F, Conversions, BanditSettings.Conversion]
    }
  }

  def apply[F[_]: Timer: Concurrent: EventLogger: NonEmptyParallel](
      mongoConfig: Config
    )(implicit ex: ExecutionContext,
      amazonClient: DynamoDbAsyncClient
    ): Resource[F, ConversionBMABAlg[F]] =
    mongo.daosResource(mongoConfig).flatMap(implicit mongo => apply)

}
