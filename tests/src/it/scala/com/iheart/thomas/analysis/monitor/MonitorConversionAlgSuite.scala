package com.iheart.thomas
package analysis
package monitor

import cats.effect.testing.scalatest.AsyncIOSpec
import com.iheart.thomas.analysis.{Conversions, KPIName}
import com.iheart.thomas.testkit.Resources.localDynamoR
import org.scalatest.matchers.should.Matchers
import cats.implicits._
import cats.effect._
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{ArmState, Key}

class MonitorConversionAlgSuite extends AsyncIOSpec with Matchers {
  import dynamo.AnalysisDAOs._

  val algR = localDynamoR.map(implicit ld => MonitorConversionAlg.default[IO])
  "MonitorAlg" - {

    "update state in parallel" in {

      val key = Key("feature1", KPIName("kpi1"))
      algR
        .use { implicit alg =>
          for {
            _ <- alg.initState(key.feature, key.kpi)
            _ <-
              List
                .fill(20)(
                  alg.updateState(key, List("A" -> false, "A" -> false, "A" -> true))
                )
                .parSequence
            updated <- alg.getState(key)
          } yield updated
        }
        .asserting {
          _.toList.flatMap(_.arms) shouldBe List(
            ArmState("A", Conversions(20, 40), None)
          )
        }

    }

    "add new arm stat automatically" in {

      val key = Key("feature1", KPIName("kpi1"))
      algR
        .use { implicit alg =>
          for {
            _ <- alg.initState(key.feature, key.kpi)
            _ <- alg.updateState(key, List("A" -> false, "A" -> false, "A" -> true))
            _ <- alg.updateState(key, List("B" -> false, "B" -> false, "A" -> true))

            updated <- alg.getState(key)
          } yield updated
        }
        .asserting {
          _.toList.flatMap(_.arms).toSet shouldBe Set(
            ArmState("A", Conversions(2, 2), None),
            ArmState("B", Conversions(0, 2), None)
          )
        }

    }
  }
}
