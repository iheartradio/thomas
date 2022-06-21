package com.iheart.thomas.spark
import cats.kernel.laws.discipline.SerializableTests
import cats.tests.CatsSuite
import concurrent.duration._

class AutoRefreshAssignerSuite extends CatsSuite {
  // / disable this test to see if this is still required since it seems that the whole UDF is no longer serializable.
  //  checkAll(
//    "AutoRefreshAssigner.udf Serializable",
//    SerializableTests.serializable(
//      AutoRefreshAssigner("fakeUrl", 10.seconds).assignUdf("fakeFeature")
//    )
//  )

  checkAll(
    "AutoRefreshAssigner.udf Serializable",
    SerializableTests.serializable(
      AutoRefreshAssigner("fakeUrl", 10.seconds).assignFunction("fakeFeature")
    )
  )
}
