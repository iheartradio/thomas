package com.iheart.thomas
package abtest
package model

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import java.time.Instant

class ModelSuite extends AnyFreeSpec
  with ScalaCheckDrivenPropertyChecks
  with Matchers {

  "Feature tests" - {
    val existingFeatureConfig: Feature = Feature(
      name = "test feature",
      overrides = Map(
        "user1" -> "control",
        "user2" -> "treatment"
      ),
      lastUpdated = Some(Instant.now())
    )

    "a new feature config with new override should not trigger nonTestSettingsChangedFrom" - {
      val newConfig: Feature = Feature(
        name = "test feature",
        overrides = Map(
          "user1" -> "treatment",
          "user2" -> "control"
        ),
        lastUpdated = None
      )
      existingFeatureConfig nonTestSettingsChangedFrom newConfig  shouldBe false
    }

    "a new feature config with new description should trigger nonTestSettingsChangedFrom" - {
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
}
