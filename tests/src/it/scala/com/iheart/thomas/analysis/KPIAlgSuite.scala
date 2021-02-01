package com.iheart.thomas
package analysis

import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import cats.implicits._
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.abtest.TestUtils.F
import com.iheart.thomas.analysis.DistributionSpec._
import com.iheart.thomas.testkit

class KPIAlgSuite extends AsyncIOSpec with Matchers {
  def withAlg[A](f: KPIModelApi[F] => F[A]): F[A] =
    testkit.Resources.apis.map(_._2).use(f)

  "KPIModelAlg" - {
    "throw NotFound when there is no distribution" in {
      withAlg(
        _.get("non-exist")
      ).asserting(_ shouldBe None)
    }

    "create one when there isn't one already" in {
      val kpi: KPIModel =
        BetaKPIModel(KPIName("new KPI"), 1d, 3d)

      withAlg(_.upsert(kpi)).asserting(_.data shouldBe kpi)
    }

    "update one" in {
      val kpi =
        LogNormalKPIModel(
          KPIName("another KPI"),
          Normal(0d, 0.1d),
          Uniform(0d, 0.6d)
        )
      val kpiUpdated: KPIModel = kpi.copy(locationPrior = Normal(1.3, 0.13d))
      withAlg { alg =>
        for {
          _ <- alg.upsert(kpi)
          _ <- alg.upsert(kpiUpdated)
          retrieved <- alg.get(kpi.name.toString)
        } yield {
          retrieved.map(_.data) shouldBe Some(kpiUpdated)
        }
      }

    }
  }

}
