package com.iheart.thomas.stream

import cats.effect.IO
import cats.effect.scalatest.AsyncIOSpec
import fs2.Stream
import org.scalatest.matchers.should.Matchers
import AsyncConversionUpdater.{Converted, Viewed}
import com.iheart.thomas.analysis.Conversions

class AsyncConversionUpdaterSuite
    extends AsyncIOSpec
    with Matchers {
  "toConversion" - {
    "count conversions per arm" in {
      val input = Stream.fromIterator[IO](
        List(
          "A" -> Viewed,
          "B" -> Converted,
          "B" -> Converted,
          "A" -> Converted,
          "B" -> Viewed,
          "B" -> Converted
        ).iterator
      )
      AsyncConversionUpdater
        .toConversion[IO](10)(input)
        .compile
        .toList
        .asserting(
          _ shouldBe List(
            Map(
              "A" -> Conversions(1, 2),
              "B" -> Conversions(1, 4)
            )
          )
        )

    }

  }

}
