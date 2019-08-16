package com.iheart.thomas
package play

import cats.effect.IO
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.analysis.SampleSettings
import com.iheart.thomas.bandit.bayesian.ConversionBMABAlg
import com.iheart.thomas.dynamo.DAOs
import com.stripe.rainier.sampler.RNG
import javax.inject._
import cats.tagless.implicits._

@Singleton
class BanditAlgsProvider @Inject()(abtestAPIProvider: AbtestAPIProvider,
                                   dcProvider: Provider[AmazonDynamoDBAsync]) {

  lazy val conversionBMABAlg: ConversionBMABAlg[IO] = {
    implicit val (ss, r) = (SampleSettings.default, RNG.default)

    ConversionBMABAlg.default[IO](
      DAOs.stateDAO(dcProvider.get()),
      abtestAPIProvider.kpiApi.mapK(abtest.apiResultTo[IO]),
      abtestAPIProvider.api.mapK(abtest.apiResultTo[IO])
    )
  }

}
