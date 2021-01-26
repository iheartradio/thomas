package com.iheart.thomas.dynamo

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.iheart.thomas.analysis.{BetaModel, ConversionKPI, KPIName}
import com.iheart.thomas.testkit.Resources.localDynamoR
import org.scalatest.matchers.should.Matchers

class ConversionKPIDAOSuite extends AsyncIOSpec with Matchers {
  val daoR = localDynamoR.map(implicit ld => AnalysisDAOs.conversionKPIDAO[IO])

  "Can insert a new KPI" in {
    val toInsert = ConversionKPI(KPIName("a"), "kai", None, BetaModel(0, 0), None)

    daoR
      .use(
        _.upsert(toInsert)
      )
      .asserting(_ shouldBe toInsert)
  }
}
