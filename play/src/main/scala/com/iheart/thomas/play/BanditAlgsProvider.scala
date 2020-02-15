package com.iheart.thomas
package play

import java.time.Instant

import cats.effect.IO
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.analysis.{Conversions, SampleSettings}
import com.iheart.thomas.bandit.bayesian.ConversionBMABAlg
import com.iheart.thomas.bandit.tracking.EventLogger
import com.iheart.thomas.dynamo.DAOs
import com.stripe.rainier.sampler.RNG
import javax.inject._

import scala.concurrent.ExecutionContext
import dynamo.DynamoFormats._
@Singleton
class BanditAlgsProvider @Inject()(
    abtestAPIProvider: AbtestAPIProvider,
    dcProvider: DynamoClientProvider,
    ec: ExecutionContext) {

  lazy val conversionBMABAlg: ConversionBMABAlg[IO] = {
    implicit val (ss, r) = (SampleSettings.default, RNG.default)
    implicit val nowF = IO.delay(Instant.now)
    implicit val t = IO.timer(ec)
    implicit val dc = dcProvider.get()
    implicit val (sd, kpiApi, api, logger) = (
      DAOs.banditState[IO, Conversions],
      abtestAPIProvider.kpiApi,
      abtestAPIProvider.api,
      EventLogger.noop[IO]
    )
    ConversionBMABAlg.default[IO]

  }

}

trait DynamoClientProvider extends Provider[AmazonDynamoDBAsync]
