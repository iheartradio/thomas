package com.iheart.thomas.analysis

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Resource}
import com.iheart.thomas.analysis.monitor.{ExperimentKPIState, ExperimentKPIStateDAO}
import org.scalatest.matchers.should.Matchers
import cats.implicits._
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{ArmState, Key}
import com.iheart.thomas.dynamo.AnalysisDAOs
import com.iheart.thomas.stream.KPIProcessAlg
import com.iheart.thomas.testkit.MapBasedDAOs
import com.iheart.thomas.testkit.Resources.localDynamoR

import java.time.Instant
import concurrent.duration._

abstract class ExperimentKPIStateDAOSuite extends AsyncIOSpec with Matchers {

  val daoR: Resource[IO, ExperimentKPIStateDAO[IO, Conversions]]

  "ExperimentKPIStateDAO" - {
    val key = Key("feature1", KPIName("kpi1"))
    "insert State correctly" in {
      daoR
        .use { implicit dao =>
          dao.ensure(key)(
            IO.pure(ExperimentKPIState(key, Nil, Instant.now, Instant.now))
          )
        }
        .asserting(_.key shouldBe key)
    }

    "update state with updated time stamp" in {
      daoR
        .use { implicit dao =>
          for {
            init <- dao.ensure(key)(
              ExperimentKPIState(key, Nil, Instant.now, Instant.now).pure[IO]
            )
            _ <- IO.sleep(100.millis)
            updated <- dao.update(key) { _ =>
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

    "add new arm stat automatically" in {

      val key = Key("feature1", KPIName("kpi1"))
      daoR
        .use { implicit dao =>
          for {
            _ <- dao.init(key)
            _ <- dao.update(key)(
              KPIProcessAlg
                .updateConversionArms(List("A" -> false, "A" -> false, "A" -> true))
            )
            _ <- dao.update(key)(
              KPIProcessAlg
                .updateConversionArms(List("B" -> false, "B" -> false, "A" -> true))
            )

            updated <- dao.get(key)
          } yield updated
        }
        .asserting {
          _.arms.toSet shouldBe Set(
            ArmState("A", Conversions(2, 2), None),
            ArmState("B", Conversions(0, 2), None)
          )
        }

    }
  }

}

class ExperimentKPIStateDAOInMemorySuite extends ExperimentKPIStateDAOSuite {
  val daoR =
    Resource.liftF(IO.delay(MapBasedDAOs.experimentStateDAO[IO, Conversions]))
}

class ExperimentKPIStateDAODynamoSuite extends ExperimentKPIStateDAOSuite {
  val daoR =
    localDynamoR.map(implicit ld => AnalysisDAOs.experimentKPIStateConversionDAO)

  "update state in parallel" in {

    val key = Key("feature1", KPIName("kpi1"))
    daoR
      .use { implicit dao =>
        for {
          _ <- dao.init(key)
          _ <-
            List
              .fill(20)(
                dao.update(key)(
                  KPIProcessAlg.updateConversionArms(
                    List("A" -> false, "A" -> false, "A" -> true)
                  )
                )
              )
              .parSequence
          updated <- dao.get(key)
        } yield updated
      }
      .asserting {
        _.arms shouldBe List(
          ArmState("A", Conversions(20, 40), None)
        )
      }

  }

}
