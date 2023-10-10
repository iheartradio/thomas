package com.iheart.thomas
package abtest
package model

import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ModelSuite extends AnyFunSuiteLike with Matchers {

  val existingFeatureConfig: Feature = Feature(
    name = "test feature",
    overrides = Map(
      "user1" -> "control",
      "user2" -> "treatment"
    ),
    lastUpdated = Some(Instant.EPOCH)
  )

  test("a new feature config with new override should not trigger nonTestSettingsChangedFrom") {
    val newConfig: Feature = Feature(
      name = "test feature",
      overrides = Map(
        "user1" -> "treatment",
        "user2" -> "control"
      ),
      lastUpdated = None
    )
    existingFeatureConfig nonTestSettingsChangedFrom newConfig shouldBe false
  }

  test("a new feature config with new description should trigger nonTestSettingsChangedFrom") {
    val newConfig: Feature = Feature(
      name = "test feature",
      description = Some("test description"),
      overrides = Map(
        "user1" -> "control",
        "user2" -> "treatment"
      ),
      lastUpdated = None
    )
    existingFeatureConfig nonTestSettingsChangedFrom newConfig shouldBe true
  }

}
