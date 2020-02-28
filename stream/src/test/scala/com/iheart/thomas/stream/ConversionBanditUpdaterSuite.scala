package com.iheart.thomas.stream

import fs2.Stream
import cats.effect.IO
import org.scalatest.matchers.should.Matchers
import ConversionBanditUpdater.{Converted, Viewed}
import cats.effect.testing.scalatest.AsyncIOSpec
import com.iheart.thomas.analysis.Conversions

class ConversionBanditUpdaterSuite extends AsyncIOSpec with Matchers {
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
      ConversionBanditUpdater
        .toConversion[IO](10)(input)
        .compile
        .toList
        .asserting(
          _ shouldBe List(
            Map(
              "A" -> Conversions(1, 2),
              "B" -> Conversions(3, 4)
            )
          )
        )

    }

  }

}
