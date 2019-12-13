/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package abtest

import java.time.OffsetDateTime

import cats.implicits._
import model._
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen._
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import scala.math.BigDecimal
import scala.math.BigDecimal.RoundingMode
import scala.util.Random

trait BucketingTestsBase
    extends AnyFunSuiteLike
    with ScalaCheckDrivenPropertyChecks
    with Matchers {
  import BucketingTests._
  def rangesConsistentWithSize(
      ranges: GroupRanges,
      groups: List[Group]
    ): Unit =
    groups.foreach { group =>
      sizeOf(ranges(group.name)) shouldBe group.size
    }

  def assertGroupDistribution(
      groupResults: List[GroupName],
      g: Group
    ) = {
    val groupSizes = groupResults.groupBy(identity).mapValues(_.length)

    (groupSizes
      .get(g.name)
      .fold(0d)(_.toDouble) / groupResults.size) should be(
      g.size.doubleValue() +- 0.1
    )
  }

  def assertRangesValid(ranges: GroupRanges) = {
    for {
      (gn, ranges1) <- ranges
      r1 <- ranges1
      r2 <- ranges.filterKeys(_ != gn).values.toList.flatten
    } {
      if (r2.end > r1.start && r1.end > r2.start)
        fail(s"$r1 and $r2 overlaps")
    }
  }
}

class BucketingTests extends BucketingTestsBase {
  import BucketingTests._
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(sizeRange = 30, minSuccessful = 30)

  test("gets the same group for the same test and profile") {
    forAll { (userId: UserId, test: Abtest) ⇒
      (0 to 100)
        .map(_ ⇒ Bucketing.getGroup(userId, test))
        .map(_.get)
        .distinct
        .size should be(1)
    }
  }

  test("gets the same group for the same test feature and profile") {
    forAll { (userId: UserId, test: Abtest, test2: Abtest) ⇒
      val test2SameFeature = test2.copy(
        feature = test.feature,
        groups = test.groups,
        ranges = test.ranges
      )
      Bucketing.getGroup(userId, test) should be(
        Bucketing.getGroup(userId, test2SameFeature)
      )
    }
  }

  test(
    "The first 2 groups should retain as many user as possible when sizes change"
  ) {
    forAll { (userIds: List[UserId], test: Abtest, test2Raw: Abtest) ⇒
      val groupResults1 =
        userIds.groupBy(uid ⇒ Bucketing.getGroup(uid, test).get)

      val test2 = test2Raw.copy(
        feature = test.feature,
        ranges = Bucketing.newRanges(test2Raw.groups, test.ranges)
      )

      val groupResults2 =
        userIds.groupBy(uid ⇒ Bucketing.getGroup(uid, test2).get)

      def ensureConsistency(group: Group) = {
        val g1 = test.groups.find(_.name == group.name).get
        val users1 = groupResults1.getOrElse(g1.name, Nil)
        val g2 = test2.groups.find(_.name == group.name).get
        val users2 = groupResults2.getOrElse(g2.name, Nil)

        if (g1.size > g2.size)
          users1 should contain allElementsOf users2
        else
          users2 should contain allElementsOf users1
      }

      test.groups.foreach(ensureConsistency)
    }
  }

  test("getGroup distribute to group size") {
    forAll(usersListG, testG) { (userIds: List[UserId], test: Abtest) ⇒
      val groupResults =
        userIds.map(uid ⇒ Bucketing.getGroup(uid, test).get)
      test.groups.foreach { group =>
        assertGroupDistribution(groupResults, group)
      }

    }
  }

  test("user id uniform distribution") {
    forAll { (test: Abtest) ⇒
      val random = new Random
      val size = 100000
      val maxId = 500000000
      val ids = List.fill(size)(random.nextInt(maxId).toDouble)
      val expectedMean = maxId.toDouble / 2d

      val groupResults = ids
        .groupBy(uid ⇒ Bucketing.getGroup(uid.toString, test).get)
        .filterKeys(k => test.groups.find(_.name == k).get.size > 0.1)

      groupResults.values.foreach { ids =>
        val meanIdInGroup = ids.sum / ids.length
        (meanIdInGroup / expectedMean) shouldBe (1d +- 0.02)
      }

    }
  }

  test(
    "newRanges return the same ranges back if they are consistent with the group sizes"
  ) {
    forAll(groupRangeGen(None)) { groupRanges: (GroupRanges, List[Group]) =>
      val (oldRanges, groups) = groupRanges
      oldRanges.values.toList.flatten.foldMap(_.size.doubleValue) should be(
        1d +- 0.000001
      )
      Bucketing.newRanges(groups, oldRanges) should be(oldRanges)
    }
  }

  test("create new set of ranges that is consistent with the group size") {
    forAll { (groups: List[Group]) =>
      val newRanges = Bucketing.newRanges(groups, Map())
      rangesConsistentWithSize(newRanges, groups)
    }
  }

  test("update ranges avoids fragmentation") {
    val oldRanges = Map(
      "A" -> List(
        GroupRange(0, 0.2),
        GroupRange(0.21, 0.52),
        GroupRange(0.78, 0.81)
      ),
      "B" -> List(
        GroupRange(0.2, 0.21),
        GroupRange(0.52, 0.78),
        GroupRange(0.81, 1)
      )
    )

    val newGroups = List(Group("A", 0.3), Group("B", 0.7))

    Bucketing.newRanges(newGroups, oldRanges) shouldBe Map(
      "A" -> List(GroupRange(0.21, 0.51)),
      "B" -> List(GroupRange(0, 0.21), GroupRange(0.51, 1))
    )
  }

  test("update ranges that is a consistent continuation") {
    implicit val groupTransitionGen
        : Gen[(GroupRanges, List[Group], List[Group], List[Group])] = for {
      length <- choose(1, 10)
      groupRanges <- groupRangeGen(length)
      groups1 <- groupsGen(length)
      groups2 <- groupsGen(length + 1)
      groups3 <- groupsGen(length)
    } yield (groupRanges._1, groups1, groups2, groups3)

    forAll(groupTransitionGen) {
      (transitions: (GroupRanges, List[Group], List[Group], List[Group])) =>
        val (oldRanges, groups1, groups2, groups3) = transitions

        def assertConsistency(
            previousRanges: GroupRanges,
            newRanges: GroupRanges,
            groups: List[Group]
          ) = {

          rangesConsistentWithSize(newRanges, groups)

          groups.foreach { group =>
            // debugging print, leave for future debugging usage
            // println("++++++++++++++++")
            // println("previousRanges")
            // println(previousRanges.get(group.name))
            // println(previousRanges.get(group.name).map(_.foldMap(_.size)))
            // println(group)
            // println("newRanges")
            // println(newRanges)
            // println(newRanges.foldMap(_.size))

            if (previousRanges.contains(group.name)) {
              val rangesToCompare =
                List(previousRanges(group.name), newRanges(group.name))

              val largerRanges = rangesToCompare.maxBy(sizeOf)
              val smallerRanges = rangesToCompare.minBy(sizeOf)

              smallerRanges.forall { sr =>
                largerRanges.exists(_.contains(sr))
              } should be(true)

            }
          }
        }

        val newRanges1 = Bucketing.newRanges(groups1, oldRanges)
        assertConsistency(oldRanges, newRanges1, groups1)
        assertRangesValid(newRanges1)

        val newRanges2 = Bucketing.newRanges(groups2, newRanges1)
        assertConsistency(newRanges1, newRanges2, groups2)

        assertRangesValid(newRanges2)

        val newRanges3 = Bucketing.newRanges(groups3, newRanges2)
        assertConsistency(newRanges2, newRanges3, groups3)

        assertRangesValid(newRanges3)
    }
  }

  test("consolidate ranges leaves separated ranges alone") {
    val discreteRanges = List(GroupRange(0, 0.3), GroupRange(0.4, 0.5))
    Bucketing.consolidateRanges(discreteRanges) should be(discreteRanges)
  }

  test("consolidate ranges merge continuous ranges") {
    val continuousRange =
      List(GroupRange(0, 0.2), GroupRange(0.3, 0.5), GroupRange(0.25, 0.3))
    Bucketing.consolidateRanges(continuousRange) should be(
      List(GroupRange(0, 0.2), GroupRange(0.25, 0.5))
    )
  }

}

class BucketingRegression extends BucketingTestsBase {
  test("regression evolve from zero") {

    val newRanges = Bucketing.newRanges(
      List(Group("A", 0.1), Group("B", 0.1)),
      Map("A" -> List(GroupRange(0d, 0d)), "B" -> List(GroupRange(0d, 0d)))
    )

    assertRangesValid(newRanges)
  }
}

object BucketingTests {

  def sizeOf(ranges: List[GroupRange]): GroupSize = ranges.foldMap(_.size)

  def createGroup(
      size: GroupSize,
      idx: Int
    ) = Group(s"group$idx", size)
  lazy val userIdGen = choose(10000, 400000).map(_.toString)

  def rangesToGroups(ranges: List[GroupRange]): (GroupRanges, List[Group]) = {
    val groupsWithRange = ranges.mapWithIndex { (range, idx) =>
      (createGroup(range.size, idx), range)
    }

    val rangeMap =
      groupsWithRange.map(gr => (gr._1.name, List(gr._2))).toMap
    val groups = groupsWithRange.map(_._1)
    (rangeMap, groups)
  }

  def sizesGen(length: Int): Gen[List[GroupSize]] =
    rangesGen(length).map(_.map(_.size))

  implicit val chooseBigDecimal: Choose[BigDecimal] =
    Choose.xmap[Double, BigDecimal](BigDecimal(_), _.doubleValue)

  def rangesGen(length: Int): Gen[List[GroupRange]] = {
    def loop(
        start: BigDecimal,
        length: Int
      ): Gen[List[GroupRange]] =
      if (length == 1) const(List(GroupRange(start, 1d)))
      else
        for {
          end <- choose(
            start + BigDecimal(0.00009),
            BigDecimal(0.9999 - (length - 1) * 0.0001)
          )
          head = GroupRange(start, end)
          tail <- loop(end, length - 1)
        } yield head :: tail
    loop(0, length)
  }

  def rangesGen(length: Option[Int]): Gen[List[GroupRange]] =
    length.fold(Gen.choose(1, 10).flatMap(rangesGen))(rangesGen)

  def groupRangeGen(length: Option[Int]): Gen[(GroupRanges, List[Group])] =
    rangesGen(length).map(rangesToGroups)
  def groupRangeGen(length: Int): Gen[(GroupRanges, List[Group])] =
    groupRangeGen(Some(length))

  implicit val rangesA: Arbitrary[List[GroupRange]] = Arbitrary(
    rangesGen(None)
  )

  // generates a set of groups whose sizes add up to 1
  def groupsGen(length: Int = 3): Gen[List[Group]] =
    sizesGen(length).map(_.mapWithIndex(createGroup))

  val groupIdxGen: Gen[Int] = choose(0, 2)

  val usersListG: Gen[List[UserId]] =
    listOfN(3000, userIdGen).suchThat(_.distinct.size > 50)

  val tenDayAgo = OffsetDateTime.now.minusDays(10)

  val tenDayAfter = OffsetDateTime.now.plusDays(10)

  val testG: Gen[Abtest] =
    for {
      feature <- arbitrary[String]
      name <- arbitrary[String]
      (ranges, groups) <- groupRangeGen(3)
    } yield Abtest(
      name,
      "author",
      feature,
      tenDayAgo.toInstant,
      Some(tenDayAfter.toInstant),
      groups,
      ranges
    )

  implicit val testA: Arbitrary[Abtest] = Arbitrary(testG)
  implicit val userListA: Arbitrary[List[UserId]] = Arbitrary(usersListG)
  implicit val groupListA: Arbitrary[List[Group]] = Arbitrary(groupsGen(3))
  implicit val noShrinkForUsers: Shrink[List[UserId]] = Shrink(
    _ => Stream.empty
  )
  implicit val noShrinkForGroupRanges: Shrink[(GroupRanges, List[Group])] =
    Shrink(_ => Stream.empty)
  implicit val noShrinkForGroupRanges3
      : Shrink[(GroupRanges, List[Group], List[Group], List[Group])] =
    Shrink(_ => Stream.empty)

}
