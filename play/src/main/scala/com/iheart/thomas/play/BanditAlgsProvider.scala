package com.iheart.thomas
package play

import java.time.Instant

import cats.effect.IO
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.analysis.Conversions
import com.iheart.thomas.bandit.bayesian.{BanditSettings, ConversionBMABAlg}
import com.iheart.thomas.bandit.tracking.EventLogger
import com.iheart.thomas.dynamo.DAOs
import com.iheart.thomas.dynamo.DynamoFormats._
import com.stripe.rainier.sampler.{RNG, Sampler}
import javax.inject._

import scala.concurrent.ExecutionContext

@Singleton
class BanditAlgsProvider @Inject()(
    abtestAPIProvider: AbtestAPIProvider,
    dcProvider: DynamoClientProvider,
    ec: ExecutionContext) {

  lazy val conversionBMABAlg: ConversionBMABAlg[IO] = {
    implicit val (ss, r) = (Sampler.default, RNG.default)
    implicit val nowF = IO.delay(Instant.now)
    implicit val t = IO.timer(ec)
    implicit val cs = IO.contextShift(ec)
    implicit val dc = dcProvider.get()
    implicit val (sd, bsd, kpiApi, api, logger) = (
      DAOs.banditState[IO, Conversions],
      DAOs.banditSettings[IO, BanditSettings.Conversion],
      abtestAPIProvider.kpiApi,
      abtestAPIProvider.api,
      EventLogger.noop[IO]
    )
    ConversionBMABAlg.default[IO]

  }

}

trait DynamoClientProvider extends Provider[AmazonDynamoDBAsync]
