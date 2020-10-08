package com.iheart.thomas.abtest

import java.time.OffsetDateTime

import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import TestUtils._
import cats.effect.IO
import cats.implicits._
import cats.effect.implicits._
class AbtestConcurrencySuite extends AsyncIOSpec with Matchers {
  "AbtestAlg" - {
    "Cannot create two tests for a new feature simultaneously" in {
      withAlg { alg =>
        List
          .fill(30) {
            val ab = fakeAb(1, 5)

            (alg.create(ab, false).attempt, alg.create(ab, false).attempt).parTupled
              .as(ab.feature)
          }
          .sequence >>= { features =>
          features.traverse { feature =>
            alg.getTestsByFeature(feature).map(_.size)
          }
        }
      }.asserting(_ shouldBe List.fill(30)(1))

    }

    "Cannot create two tests for an existing feature simultaneously" in {
      withAlg { alg =>
        List
          .fill(30) {
            val ab = fakeAb(1, 2)
            val ab2 = fakeAb(3, 7, feature = ab.feature)
            alg.create(ab, false) >>
              (alg.create(ab2, false).attempt, alg.create(ab2, false).attempt).parTupled
                .as(ab.feature)
          }
          .sequence >>= { features =>
          features.traverse { feature =>
            alg.getTestsByFeature(feature).map(_.size)
          }
        }
      }.asserting(_ shouldBe List.fill(30)(2))

    }

    "Two attempts to auto create a test should end up with one test" in {
      withAlg { alg =>
        List
          .fill(30) {
            val ab = fakeAb(1, 5)
            val ab2 = fakeAb(3, 7, feature = ab.feature).copy(name = "new version")
            alg.create(ab, false) >>
              (alg.create(ab2, true).attempt, alg.create(ab2, true).attempt).parTupled
                .as(ab.feature)
          }
          .sequence >>= { features =>
          features.traverse { feature =>
            alg
              .getTestsByFeature(feature)
              .map(ts => (ts.size, ts.head.data.name))
          }
        }
      }.asserting(_ shouldBe List.fill(30)((1, "new version")))

    }

    "Cannot have two attempts of continuing a test simultaneously" in {
      withAlg { alg =>
        List
          .fill(30) {
            val ab = fakeAb(1)
            val ab2 = fakeAb(3, 6, feature = ab.feature)
            val ab3 = fakeAb(8, 9, feature = ab.feature)
            alg.create(ab, false) >>
              (alg.continue(ab2).attempt, alg.continue(ab2).attempt).parTupled
                .as(ab.feature)
          }
          .sequence >>= { features =>
          features.traverse { feature =>
            alg
              .getTestsByFeature(feature)
              .map(_.size)
          }
        }
      }.asserting(_ shouldBe List.fill(30)(2))

    }
  }
}
