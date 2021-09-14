package com.iheart.thomas.http4s.analysis

import cats.data.Chain
import cats.data.Validated.Valid
import com.iheart.thomas.analysis.bayesian.models.BetaModel
import com.iheart.thomas.analysis.{ConversionKPI, KPIName}
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AsyncFreeSpec

class DecoderSuite extends AnyFunSuiteLike with Matchers {

  test("can read conversion kpi form") {
    UI.Decoders.conversionKPIDecoder.apply(
      Map(
        "name" -> Chain("foo"),
        "author" -> Chain("bar"),
        "model.alpha" -> Chain("1"),
        "model.beta" -> Chain("2")
      )
    ) should be(
      Valid(ConversionKPI(KPIName("foo"), "bar", None, BetaModel(1, 2), None))
    )

  }
}
