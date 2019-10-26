package com.iheart.thomas.kafka

import java.time.OffsetDateTime

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.analysis.SampleSettings
import com.iheart.thomas.bandit.BanditStateDAO
import com.iheart.thomas.bandit.bayesian.ConversionBMABAlg
import com.iheart.thomas.{dynamo, mongo}
import com.stripe.rainier.sampler.RNG
import com.typesafe.config.Config

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object ConversionBMABAlgResource {

  def apply[F[_]: Timer: ContextShift](
      implicit ex: ExecutionContext,
      F: ConcurrentEffect[F],
      mongoDAOs: mongo.DAOs[F],
      amazonClient: AmazonDynamoDBAsync
    ): Resource[F, ConversionBMABAlg[F]] = {
    import mongo.idSelector
    implicit val stateDAO =
      BanditStateDAO.bayesianfromLihua(
        dynamo.DAOs.lihuaStateDAO[F](amazonClient)
      )
    implicit val (abtestDAO, featureDAO, kpiDAO) = mongoDAOs
    lazy val refreshPeriod = 0.seconds

    AbtestAlg.defaultResource[F](refreshPeriod).map { implicit abtestAlg =>
      implicit val ss = SampleSettings.default
      implicit val rng = RNG.default
      implicit val nowF = F.delay(OffsetDateTime.now)
      ConversionBMABAlg.default[F]
    }
  }

  def apply[F[_]: Timer: ContextShift: ConcurrentEffect](
      mongoConfig: Config
    )(implicit ex: ExecutionContext,
      amazonClient: AmazonDynamoDBAsync
    ): Resource[F, ConversionBMABAlg[F]] =
    mongo.daosResource(mongoConfig).flatMap(implicit mongo => apply)

}
