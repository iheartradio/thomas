package com.iheart.thomas
package stream

import analysis._
import cats.Id
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers
import cats.implicits._
import com.iheart.thomas.analysis.{ConversionMessageQuery, Criteria, MessageQuery}
import org.typelevel.jawn.ast._

class JValueParserSuite extends AnyFreeSpecLike with Matchers {
  val parser = ConversionParser.jValueParser[Id]
  "JValue Parser" - {
    "parse from regex" in {
      val query = ConversionMessageQuery(
        initMessage = MessageQuery(None, List(Criteria("foo.bar", "abc"))),
        convertedMessage = MessageQuery(None, List(Criteria("bar", "^abc$")))
      )

      parser.parseConversion(
        JObject.fromSeq(
          Seq("foo" -> JObject.fromSeq(Seq(("bar" -> JString("xxxabcxxx")))))
        ),
        query
      ) shouldBe Some(Initiated)

      parser.parseConversion(
        JObject.fromSeq(Seq("bar" -> JString("abc"))),
        query
      ) shouldBe Some(Converted)

      parser.parseConversion(
        JObject.fromSeq(Seq("bar" -> JString("abc2"))),
        query
      ) shouldBe None
    }
  }
}
