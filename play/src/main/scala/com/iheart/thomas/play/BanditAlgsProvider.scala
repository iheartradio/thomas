package com.iheart.thomas
package play

import java.time.OffsetDateTime

import cats.effect.IO
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.analysis.SampleSettings
import com.iheart.thomas.bandit.bayesian.ConversionBMABAlg
import com.iheart.thomas.dynamo.DAOs
import com.stripe.rainier.sampler.RNG
import javax.inject._

@Singleton
class BanditAlgsProvider @Inject()(
    abtestAPIProvider: AbtestAPIProvider,
    dcProvider: DynamoClientProvider) {

  lazy val conversionBMABAlg: ConversionBMABAlg[IO] = {
    implicit val (ss, r) = (SampleSettings.default, RNG.default)
    implicit val nowF = IO.delay(OffsetDateTime.now)
    implicit val (sd, kpiApi, api) = (
      DAOs.stateDAO[IO](dcProvider.get()),
      abtestAPIProvider.kpiApi,
      abtestAPIProvider.api
    )
    ConversionBMABAlg.default[IO]

  }

}

trait DynamoClientProvider extends Provider[AmazonDynamoDBAsync]
