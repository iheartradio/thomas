package com.iheart.thomas.analysis

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.iheart.thomas.analysis.bayesian.models.BetaModel
import com.iheart.thomas.dynamo.AnalysisDAOs
import com.iheart.thomas.testkit.Resources.localDynamoR
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

class ConversionKPIRepoSuite extends AsyncFreeSpec with AsyncIOSpec with Matchers {
  val daoR = localDynamoR.map(implicit ld => AnalysisDAOs.conversionKPIRepo[IO])

  "Can insert a new KPI" in {
    val toInsert =
      ConversionKPI(KPIName("a"), "kai", None, BetaModel(0.1, 0.1), None)

    daoR
      .use(
        _.create(toInsert)
      )
      .asserting(_ shouldBe toInsert)
  }
}
