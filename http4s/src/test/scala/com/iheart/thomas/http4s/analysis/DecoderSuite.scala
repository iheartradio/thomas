package com.iheart.thomas.http4s.analysis

import cats.data.Chain
import cats.data.Validated.Valid
import com.iheart.thomas.analysis.{BetaModel, ConversionKPI, KPIName}
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers

class DecoderSuite extends AnyFunSuiteLike with Matchers {

  test("can read data") {
    UI.Decoders.conversionKPIDecoder.apply(
      Map(
        "name" -> Chain("foo"),
        "model.alphaPrior" -> Chain("1"),
        "model.betaPrior" -> Chain("2")
      )
    ) should be(Valid(ConversionKPI(KPIName("foo"), None, BetaModel(1, 2), None)))

  }
}
