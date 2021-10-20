package com.iheart.thomas.abtest

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.implicits._
import com.iheart.thomas.abtest.TestUtils._
import com.iheart.thomas.abtest.model.{Abtest, GroupRange}
import com.iheart.thomas.{GroupName, UserId}
import lihua.Entity
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

class ContinuationSuite extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  def getGroupAssignment(
      test: Entity[Abtest],
      ids: List[UserId]
    )(implicit alg: AbtestAlg[F]
    ): F[Map[GroupName, List[UserId]]] = {

    ids
      .traverse { uid =>
        alg
          .getGroupsWithMeta(
            q(
              uid.toString,
              at = Some(test.data.start.plusSeconds(1))
            )
          )
          .map(_.groups.get(test.data.feature).map((_, uid)))
      }
      .map(_.flatten.groupBy(_._1).map { case (k, v) => (k, v.map(_._2)) })
  }

  "Inherits as many users from previous test as possible" in {
    val ids = List.fill(1000)(randomUserId)
    withAlg { implicit alg =>
      for {
        ab1 <- alg.create(
          fakeAb(1, 2, "a_feature")
            .copy(groups = List(group("A", 0.3), group("B", 0.3), group("C", 0.2)))
        )
        ab2 <- alg.create(
          fakeAb(3, 4, "a_feature")
            .copy(groups = List(group("D", 0.2), group("A", 0.5), group("B", 0.2)))
        )
        ab3 <- alg.create(
          fakeAb(5, 6, "a_feature")
            .copy(groups = List(group("B", 0.1), group("A", 0.6), group("C", 0.2)))
        )

        groupAssignment1 <- getGroupAssignment(ab1, ids)
        groupAssignment2 <- getGroupAssignment(ab2, ids)
        groupAssignment3 <- getGroupAssignment(ab3, ids)
      } yield {
        //expanding group should see all users from the same group in the previous test
        groupAssignment2("A") should contain allElementsOf groupAssignment1("A")
        groupAssignment3("A") should contain allElementsOf groupAssignment2("A")

        //shrinking group should inherit all users from the same group in the previous test
        groupAssignment1("B") should contain allElementsOf groupAssignment2("B")
        groupAssignment2("B") should contain allElementsOf groupAssignment3("B")
      }

    }

  }

  "grouping should be deterministic regardless of time or machine" in {

    val ids = (253 until 319).toList.map(_.toString)

    withAlg { implicit alg =>
      for {
        ab1 <- alg.create(
          fakeAb(
            1,
            2,
            "a_feature",
            groups = List(group("A", 0.3), group("B", 0.3), group("C", 0.2))
          )
        )
        ab2 <- alg.create(
          fakeAb(
            3,
            4,
            "a_feature",
            groups = List(group("D", 0.2), group("A", 0.5), group("B", 0.2))
          )
        )
        ab3 <- alg.create(
          fakeAb(
            5,
            6,
            "a_feature",
            groups = List(group("B", 0.1), group("A", 0.6), group("C", 0.2))
          )
        )

        groupAssignment1 <- getGroupAssignment(ab1, ids)
        groupAssignment2 <- getGroupAssignment(ab2, ids)
        groupAssignment3 <- getGroupAssignment(ab3, ids)
      } yield {

        //hard coded to make sure that these values do not change for to maintain compatibility.
        groupAssignment1("A") should be(
          List(314, 310, 303, 299, 292, 288, 286, 285, 278, 273, 267, 265, 261, 257,
            256, 254).sorted.map(_.toString)
        )
        groupAssignment1("B") should be(
          List(317, 312, 311, 306, 302, 298, 294, 291, 279, 277, 275, 274, 271, 268,
            266, 262, 260).sorted.map(_.toString)
        )
        groupAssignment2("A") should be(
          List(316, 315, 314, 313, 310, 307, 304, 303, 299, 297, 296, 293, 292, 289,
            288, 286, 285, 284, 283, 282, 281, 278, 276, 273, 267, 265, 264, 261,
            259, 257, 256, 254).sorted.map(_.toString)
        )
        groupAssignment2("B") should be(
          List(317, 312, 306, 298, 294, 291, 277, 271, 268, 262, 260).sorted.map(
            _.toString
          )
        )
        groupAssignment3("A") should be(
          List(316, 315, 314, 313, 310, 309, 308, 307, 305, 304, 303, 301, 299, 297,
            296, 295, 293, 292, 290, 289, 288, 286, 285, 284, 283, 282, 281, 278,
            276, 273, 270, 267, 265, 264, 261, 259, 258, 257, 256, 255, 254,
            253).sorted.map(_.toString)
        )
        groupAssignment3("B") should be(
          List(312, 298, 277, 268).sorted.map(_.toString)
        )

      }

    }

  }

  "regression range evolution" in {
    val originalSpec =
      fakeAb(start = 1, groups = List(group("A", 0), group("B", 0)))

    withAlg { alg =>
      for {
        _ <- alg.create(originalSpec)
        continued <- alg.create(
          fakeAb(
            feature = originalSpec.feature,
            start = 2,
            groups = List(group("A", 0.1), group("B", 0.1))
          ),
          auto = true
        )
      } yield {
        continued.data.ranges shouldBe Map(
          "A" -> List(GroupRange(0, 0.1)),
          "B" -> List(GroupRange(0.1, 0.2))
        )
      }

    }

  }

  "zero sized group has no range" in {
    val spec = fakeAb(start = 1, groups = List(group("A", 0), group("B", 0)))

    withAlg { alg =>
      for {
        created <- alg.create(spec)
        assignment <- alg.getGroupsWithMeta(
          q(randomUserId, Some(spec.start.plusMinutes(1)))
        )
      } yield {
        created.data.ranges.values.forall(_.isEmpty) shouldBe true
        assignment.groups shouldBe empty
      }

    }
  }

}
