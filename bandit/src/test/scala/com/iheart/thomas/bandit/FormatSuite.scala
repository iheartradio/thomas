package com.iheart.thomas.bandit

import java.time.OffsetDateTime

import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import Formats._
import com.iheart.thomas.bandit.bayesian.{BanditSettings, ConversionBanditSpec}
import play.api.libs.json.{Format, JsSuccess, Json}

import concurrent.duration._
class FormatSuite extends AnyFunSuiteLike with Matchers {

  test("read BanditSpec Json") {
    val json =
      s"""
        |{
        |  "start": "2020-03-09T16:15:00.000-05:00",
        |  "arms": [
        |  { "name" : "T1"},
        |  { "name" : "T2"},
        |  { "name" : "T3"},
        |  { "name" : "T4"},
        |  { "name" : "T5"},
        |  { "name" : "T6"}],
        |  "settings": {
        |    "feature": "Search_Opt_Alpha",
        |    "author": "Kai",
        |    "title": "Initial Test",
        |    "kpiName":  "Search Conversion 2",
        |    "minimumSizeChange": 0.001,
        |    "initialSampleSize": 500,
        |    "historyRetention": "32d",
        |    "iterationDuration": "24h",
        |    "oldHistoryWeight": 0.5,
        |    "distSpecificSettings": {
        |    	"eventChunkSize": 50,
        |    	"updatePolicyEveryNChunk": 3
        |    }
        |  }
        |}
        |""".stripMargin

    implicitly[Format[ConversionBanditSpec]]
      .reads(Json.parse(json)) shouldBe JsSuccess(
      BanditSpec(
        start = OffsetDateTime.parse("2020-03-09T16:15:00.000-05:00"),
        arms = List("T1", "T2", "T3", "T4", "T5", "T6").map(ArmSpec(_)),
        settings = BanditSettings(
          feature = "Search_Opt_Alpha",
          author = "Kai",
          title = "Initial Test",
          kpiName = "Search Conversion 2",
          minimumSizeChange = 0.001,
          initialSampleSize = 500,
          oldHistoryWeight = Some(0.5d),
          historyRetention = Some(32.days),
          iterationDuration = Some(1.day),
          distSpecificSettings = BanditSettings.Conversion(
            eventChunkSize = 50,
            updatePolicyEveryNChunk = 3
          )
        )
      )
    )
  }

}
