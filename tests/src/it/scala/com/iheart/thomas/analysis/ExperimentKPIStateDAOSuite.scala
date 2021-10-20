package com.iheart.thomas.analysis

import cats.data._
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Resource}
import cats.implicits._
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{
  ArmState,
  ArmsState,
  Key
}
import com.iheart.thomas.analysis.monitor.{ExperimentKPIState, ExperimentKPIStateDAO}
import com.iheart.thomas.dynamo.AnalysisDAOs
import com.iheart.thomas.stream.{ArmKPIEvents, KPIProcessAlg}
import com.iheart.thomas.testkit.MapBasedDAOs
import com.iheart.thomas.testkit.Resources.localDynamoR
import com.iheart.thomas.utils.time.Period
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration._

abstract class ExperimentKPIStateDAOSuite
    extends AsyncFreeSpec
    with AsyncIOSpec
    with Matchers {

  def stateOf(events: List[(String, ConversionEvent)]) =
    KPIProcessAlg
      .statsOf(
        events.map { case (arm, e) =>
          ArmKPIEvents(arm, NonEmptyChain.one(e), Instant.now)
        }
      )
      .get

  val mockPeriod: Period = Period.of(Instant.now, Instant.now)

  def updateState(
      newState: ArmsState[Conversions]
    )(existing: ArmsState[Conversions],
      period: Period
    ): (ArmsState[Conversions], Period) =
    (KPIProcessAlg.updateArms(newState, existing), period)

  def insert(
      key: Key,
      state: ArmsState[Conversions]
    )(implicit dao: ExperimentKPIStateDAO[IO, Conversions]
    ): IO[ExperimentKPIState[Conversions]] = {
    dao.upsert(key)(null)((state, mockPeriod))
  }

  def update(
      key: Key,
      newState: ArmsState[Conversions]
    )(implicit dao: ExperimentKPIStateDAO[IO, Conversions]
    ): IO[ExperimentKPIState[Conversions]] = {
    dao.upsert(key)(updateState(newState))(null)
  }

  val daoR: Resource[IO, ExperimentKPIStateDAO[IO, Conversions]]

  "ExperimentKPIStateDAO" - {
    val key = Key("feature1", KPIName("kpi1"))

    "upsert state with updated lastUpdated" in {
      val state = NonEmptyList.one(ArmState("A", Conversions(1, 4), None))
      daoR
        .use { implicit dao =>
          for {
            init <- insert(key, state)
            _ <- IO.sleep(100.millis)
            updated <- update(key, state)
          } yield (init, updated)
        }
        .asserting { case (init, updated) =>
          updated.lastUpdated.isAfter(init.lastUpdated) shouldBe true
          updated.arms shouldBe NonEmptyList.one(
            ArmState("A", Conversions(2, 8), None)
          )
        }

    }

    "add new arm stat automatically" in {

      val key = Key("feature1", KPIName("kpi1"))
      daoR
        .use { implicit dao =>
          for {
            _ <- insert(key, stateOf(List("A" -> false, "A" -> false, "A" -> true)))
            _ <- update(key, stateOf(List("B" -> false, "B" -> false, "A" -> true)))
            updated <- dao.get(key)
          } yield updated
        }
        .asserting {
          _.arms.toList.toSet shouldBe Set(
            ArmState("A", Conversions(2, 2), None),
            ArmState("B", Conversions(0, 2), None)
          )
        }

    }
  }

}

class ExperimentKPIStateDAOInMemorySuite extends ExperimentKPIStateDAOSuite {
  val daoR =
    Resource.eval(IO.delay(MapBasedDAOs.experimentStateDAO[IO, Conversions]))
}

class ExperimentKPIStateDAODynamoSuite extends ExperimentKPIStateDAOSuite {
  val daoR =
    localDynamoR.map(implicit ld => AnalysisDAOs.experimentKPIStateConversionDAO)

  "update state in parallel" in {

    val key = Key("feature1", KPIName("kpi1"))
    val state = stateOf(
      List("A" -> false, "A" -> false, "A" -> true)
    )
    daoR
      .use { implicit dao =>
        for {
          _ <- insert(key, state)
          _ <-
            List
              .fill(19)(
                update(key, state)
              )
              .parSequence
          updated <- dao.get(key)
        } yield updated
      }
      .asserting {
        _.arms shouldBe NonEmptyList.one(
          ArmState("A", Conversions(20, 40), None)
        )
      }

  }

}
