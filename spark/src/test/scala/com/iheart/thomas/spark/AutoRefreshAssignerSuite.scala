package com.iheart.thomas.spark
import cats.kernel.laws.discipline.SerializableTests
import cats.tests.CatsSuite
import concurrent.duration._

class AutoRefreshAssignerSuite extends CatsSuite {
  checkAll(
    "AutoRefreshAssigner.udf Serializable",
    SerializableTests.serializable(
      AutoRefreshAssigner("fakeUrl", 10.seconds).assignUdf("fakeFeature")
    )
  )
}
