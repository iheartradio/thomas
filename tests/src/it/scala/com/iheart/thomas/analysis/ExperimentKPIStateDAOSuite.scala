package com.iheart.thomas.analysis

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Resource}
import com.iheart.thomas.analysis.monitor.{
  ExperimentKPIState,
  ExperimentKPIStateDAO,
  MonitorAlg
}
import org.scalatest.matchers.should.Matchers
import cats.implicits._
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{ArmState, Key}
import com.iheart.thomas.dynamo.AnalysisDAOs
import com.iheart.thomas.testkit.MapBasedDAOs
import com.iheart.thomas.testkit.Resources.localDynamoR

import concurrent.duration._

abstract class ExperimentKPIStateDAOSuite(
    val daoR: Resource[IO, ExperimentKPIStateDAO[IO, Conversions]])
    extends AsyncIOSpec
    with Matchers {

  "ExperimentKPIStateDAO" - {
    "insert State correctly" in {
      daoR
        .use { implicit dao =>
          MonitorAlg[IO].initConversion("feature1", KPIName("kpi1"))
        }
        .asserting(_.key shouldBe Key("feature1", KPIName("kpi1")))
    }

    "update state with updated time stamp" in {
      daoR
        .use { implicit dao =>
          for {
            init <- MonitorAlg[IO].initConversion("feature1", KPIName("kpi1"))
            _ <- IO.sleep(100.millis)
            updated <- dao.updateState(Key("feature1", KPIName("kpi1"))) { _ =>
              List(ArmState("A", Conversions(1, 4), None))
            }
          } yield (init, updated)
        }
        .asserting {
          case (init, updated) =>
            updated.lastUpdated.isAfter(init.lastUpdated) shouldBe true
            updated.arms shouldBe List(ArmState("A", Conversions(1, 4), None))
        }

    }
  }

}

class ExperimentKPIStateDAOInMemorySuite
    extends ExperimentKPIStateDAOSuite(
      Resource.liftF(IO.delay(MapBasedDAOs.experimentStateDAO[IO, Conversions]))
    )

class ExperimentKPIStateDAODynamoSuite
    extends ExperimentKPIStateDAOSuite(
      localDynamoR.map(implicit ld => AnalysisDAOs.experimentKPIStateConversionDAO)
    )
