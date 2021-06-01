package com.iheart.thomas.dynamo

import com.iheart.thomas.bandit.bayesian.BanditSettings
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import org.scanamo.DynamoFormat
import DynamoFormats._
class FormatSuite extends AnyFunSuiteLike with Matchers {

  test("dynamo format") {

    implicitly[
      DynamoFormat[BanditSettings[BanditSettings.Conversion]]
    ] should not be (null)
  }
}
