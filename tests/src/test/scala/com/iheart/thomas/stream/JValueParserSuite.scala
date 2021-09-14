package com.iheart.thomas
package stream

import analysis._
import cats.Id
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AsyncFreeSpec
import cats.implicits._
import com.iheart.thomas.analysis.{ConversionMessageQuery, Criteria, MessageQuery}
import org.typelevel.jawn.ast._

class JValueParserSuite extends AnyFreeSpecLike with Matchers {
  import KpiEventParser.parseConversionEvent
  "JValue Parser" - {
    "parse single event from regex" in {
      val query = ConversionMessageQuery(
        initMessage = MessageQuery(None, List(Criteria("foo.bar", "abc"))),
        convertedMessage = MessageQuery(None, List(Criteria("bar", "^abc$")))
      )

      parseConversionEvent(
        JObject.fromSeq(
          Seq("foo" -> JObject.fromSeq(Seq(("bar" -> JString("xxxabcxxx")))))
        ),
        query
      ) shouldBe List(Initiated)

      parseConversionEvent(
        JObject.fromSeq(Seq("bar" -> JString("abc"))),
        query
      ) shouldBe List(Converted)

      parseConversionEvent(
        JObject.fromSeq(Seq("bar" -> JString("abc2"))),
        query
      ) shouldBe Nil
    }

    "parse multiple event from regex" in {
      val query = ConversionMessageQuery(
        initMessage = MessageQuery(None, List(Criteria("display", "^search$"))),
        convertedMessage = MessageQuery(None, List(Criteria("action", "^click$")))
      )

      parseConversionEvent(
        JObject.fromSeq(
          Seq("display" -> JString("search"), "action" -> JString("click"))
        ),
        query
      ).toSet shouldBe Set(Converted, Initiated)

    }
  }
}
