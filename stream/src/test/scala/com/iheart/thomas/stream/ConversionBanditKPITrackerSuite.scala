package com.iheart.thomas.stream

import cats.effect.IO
import fs2.Stream
import org.scalatest.matchers.should.Matchers
import ConversionBanditKPITracker.{Converted, Viewed}
import cats.effect.scalatest.AsyncIOSpec
import com.iheart.thomas.analysis.Conversions

class ConversionBanditKPITrackerSuite extends AsyncIOSpec with Matchers {
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
      ConversionBanditKPITracker
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
