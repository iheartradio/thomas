package com.iheart.thomas
package kafka

import java.time.Instant

import cats.effect._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.analysis.Conversions
import com.iheart.thomas.bandit.bayesian.{BanditSettings, ConversionBMABAlg}
import com.iheart.thomas.bandit.tracking.EventLogger
import com.iheart.thomas.{dynamo, mongo}
import com.stripe.rainier.sampler.{RNG, Sampler}
import com.typesafe.config.Config

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import dynamo.DynamoFormats._

object ConversionBMABAlgResource {
  def apply[F[_]: Timer: EventLogger](
      implicit ex: ExecutionContext,
      F: Concurrent[F],
      mongoDAOs: mongo.DAOs[F],
      amazonClient: AmazonDynamoDBAsync
    ): Resource[F, ConversionBMABAlg[F]] = {
    import mongo.idSelector
    implicit val stateDAO =
      dynamo.DAOs.banditState[F, Conversions]
    implicit val settingDAO =
      dynamo.DAOs.banditSettings[F, BanditSettings.Conversion]
    implicit val (abtestDAO, featureDAO, kpiDAO) = mongoDAOs
    lazy val refreshPeriod = 0.seconds //No cache is needed for abtests in Conversion API

    AbtestAlg.defaultResource[F](refreshPeriod).map { implicit abtestAlg =>
      implicit val ss = Sampler.default
      implicit val rng = RNG.default
      implicit val nowF = F.delay(Instant.now)
      ConversionBMABAlg.default[F]
    }
  }

  def apply[F[_]: Timer: Concurrent: EventLogger](
      mongoConfig: Config
    )(implicit ex: ExecutionContext,
      amazonClient: AmazonDynamoDBAsync
    ): Resource[F, ConversionBMABAlg[F]] =
    mongo.daosResource(mongoConfig).flatMap(implicit mongo => apply)

}
