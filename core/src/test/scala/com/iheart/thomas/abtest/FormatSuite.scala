package com.iheart.thomas
package abtest

import java.time.OffsetDateTime

import com.iheart.thomas.abtest.json.play.Formats._
import com.iheart.thomas.abtest.model.UserMetaCriterion._
import com.iheart.thomas.abtest.model._
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import play.api.libs.json._

import scala.util.matching.Regex

class FormatSuite extends AnyFunSuiteLike with Matchers {

  val json =
    s"""
      |{
      |   "name": "Abtest for Bayesian MAB MultiArmBanditAA_2",
      |   "feature": "MultiArmBanditAA_2",
      |   "author": "Kai",
      |   "start": "2020-04-28T13:36:01.141Z",
      |   "groups": [
      |       {
      |           "name": "T2",
      |           "size": 0
      |       }
      |   ],
      |   "ranges": {
      |       "T2": [
      |           {
      |               "start": 0,
      |               "end": 1
      |           }
      |       ]
      |   },
      |   "requiredTags": [],
      |   "segmentRanges": [],
      |   "userMetaCriteria": {
      |     "sex" : "female",
      |     "age" : {
      |       "$$gt" : 32
      |     },
      |     "description" : {
      |        "$$regex" : "shinny"
      |     },
      |     "device" : {
      |       "$$in": ["iphone","ipad"]
      |     },
      |     "$$or": [
      |       { "city": "LA" },
      |       { "city": "NY" }
      |     ],
      |     "clientVer": {
      |       "$$versionStart" : "1.0.0"
      |     },
      |
      |     "androidVer": {
      |       "$$versionRange" : ["2.0", "3.1"]
      |     }
      |   },
      |   "groupMetas": {},
      |   "_id": {
      |       "$$oid": "5ea831411600005f84197e28"
      |   }
      |}
      |""".stripMargin

  val result = implicitly[Format[Abtest]]
    .reads(Json.parse(json))
  if (result.isError) println(result)
  val abtest = result.get
  val userMetaCriteria = abtest.userMetaCriteria.get

  test("reads userMetaCriteria") {
    userMetaCriteria.criteria should contain(ExactMatch("sex", "female"))
    userMetaCriteria.criteria should contain(RegexMatch("description", "shinny"))
    userMetaCriteria.criteria should contain(Greater("age", 32d))
    userMetaCriteria.criteria should contain(
      or(ExactMatch("city", "LA"), ExactMatch("city", "NY"))
    )
    userMetaCriteria.criteria should contain(VersionRange("clientVer", "1.0.0"))
    userMetaCriteria.criteria should contain(
      InMatch("device", Set("ipad", "iphone"))
    )
    userMetaCriteria.criteria should contain(
      VersionRange("androidVer", "2.0", Some("3.1"))
    )
  }

  test("read write identity") {
    Json.toJson(abtest).as[Abtest] shouldBe abtest
  }

  test("can read empty user meta criteria") {
    val emptyJson =
      """

        |{
        |   "name": "xxx",
        |   "feature": "xxxx",
        |   "author": "Kai",
        |   "start": "2020-04-28T13:36:01.141Z",
        |   "groups": []
        |}
        |""".stripMargin
    val result = implicitly[Format[AbtestSpec]]
      .reads(Json.parse(emptyJson))
    if (result.isError) println(result)
    result.get.userMetaCriteria shouldBe None
  }

}
