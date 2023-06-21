package com.iheart.thomas
package stream

import com.iheart.thomas.abtest.model.TestId

import java.time.Instant


sealed trait AdminEvent extends Serializable

object AdminEvent {
    case class FeatureChanged(name: FeatureName, updated: Instant) extends AdminEvent
    case class TestCreated(name: FeatureName, testId: TestId) extends AdminEvent
    case class TestUpdated(testId: TestId) extends AdminEvent
}
