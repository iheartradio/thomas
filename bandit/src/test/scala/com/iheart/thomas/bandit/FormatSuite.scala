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
        |  "feature": "Search_Opt_Alpha", 
        |  "arms": ["T1", "T2", "T3", "T4", "T5", "T6"],
        |  "author": "Kai",
        |  "start": "2020-03-09T16:15:00.000-05:00",
        |  "title": "Initial Test", 
        |  "kpiName":  "Search Conversion 2", 
        |  "minimumSizeChange": 0.001, 
        |  "initialSampleSize": 500,
        |  "historyRetention": ${72 * 3600 * 1000000},
        |  "specificSettings": {
        |  	"eventChunkSize": 5,
        |  	"reallocateEveryNChunk": 3
        |  }
        |}
        |""".stripMargin

    implicitly[Format[ConversionBanditSpec]]
      .reads(Json.parse(json)) shouldBe JsSuccess(
      BanditSpec(
        feature = "Search_Opt_Alpha",
        arms = List("T1", "T2", "T3", "T4", "T5", "T6"),
        author = "Kai",
        start = OffsetDateTime.parse("2020-03-09T16:15:00.000-05:00"),
        title = "Initial Test",
        kpiName = "Search Conversion 2",
        minimumSizeChange = 0.001,
        initialSampleSize = 500,
        historyRetention = Some(1501962240.nanos),
        specificSettings = BanditSettings.Conversion(
          eventChunkSize = 5,
          reallocateEveryNChunk = 3
        )
      )
    )
  }

}
