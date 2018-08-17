/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart
package thomas

import com.iheart.thomas.model._
import cats.implicits._

private[thomas] object Bucketing {

  import java.math.MathContext

  import scala.math.{BigInt, BigDecimal}
  import java.security.MessageDigest

  private val max128BitValue = { BigDecimal(BigInt(1).setBit(128)) }

  private def md5(s: String): Array[Byte] = {
    val instance = MessageDigest.getInstance("MD5")
    instance.digest(s.getBytes)
  }

  private def md5BigInt(s: String): BigInt =
    BigInt(1, md5(s))

  /**
   *
   * @param s
   * @return Between 0 and 1
   */
  def md5Double(s: String): Double = {
    (BigDecimal(md5BigInt(s), MathContext.DECIMAL32) / max128BitValue).doubleValue()
  }

  def getGroup(userId: UserId, test: Abtest): Option[GroupName] = {
    val hashValue = md5Double(test.feature + userId + test.salt.getOrElse(""))
    test.ranges.collectFirst {
      case (groupName, ranges) if ranges.exists(_.contains(hashValue)) ⇒ groupName
    }
  }

  def consolidateRanges(list: List[GroupRange]): List[GroupRange] = {
    list.sortBy(_.start).foldLeft(List.empty[GroupRange]) { (memo, range) =>
      memo match {
        case head :: tail if head.end == range.start => GroupRange(head.start, range.end) :: tail
        case _                                       => range :: memo
      }
    }.reverse.filter(_.size > 0)
  }

  def newRanges(groups: List[Group], oldRanges: GroupRanges): GroupRanges = {
    val consistent = groups.forall { group =>
      oldRanges.get(group.name).fold(false) { ranges =>
        ranges.foldMap(r => r.end - r.start) == group.size
      }
    }

    def initRanges =
      groups.filter(_.size > 0).foldLeft(Vector.empty[(GroupName, List[GroupRange])]) { (ranges, group) ⇒
        val start = ranges.lastOption.map(_._2.head.end).getOrElse(0d)
        val end = Math.min(1d, start + group.size)
        ranges :+ (group.name -> List(GroupRange(start, end)))
      }.toMap

    if (consistent) oldRanges
    else if (oldRanges.isEmpty)
      initRanges
    else {
      val removeOutdatedAssignments = oldRanges.filter { case (n, ranges) => groups.exists(_.name == n) && ranges.map(_.size).sum > 0 }
      val groupsInSurplusOrder = groups.sortBy(g => oldRanges.get(g.name).fold(0d) { rs =>
        g.size - rs.foldMap(_.size)
      })

      groupsInSurplusOrder.foldLeft(removeOutdatedAssignments) { (assigned, group) =>
        def allAvailable = {
          val (as, last) = assigned.values.toList.flatten.sortBy(_.start).foldLeft((List.empty[GroupRange], 0d)) { (memo, assignedSlot) =>
            val (allocated, start) = memo
            val allocateMore = if (assignedSlot.start > start)
              GroupRange(start, assignedSlot.start) :: allocated
            else allocated
            (allocateMore, assignedSlot.end)
          }
          if (last < 1) GroupRange(last, 1) :: as else as
        }

        def allocate(neededSize: GroupSize, available: List[GroupRange]): List[GroupRange] = {
          def loop(rest: GroupSize, rangesLeft: List[GroupRange]): List[GroupRange] = {
            if (rest < 1E-10) Nil //needed due to double accuracy
            else
              rangesLeft match {
                case head :: tail =>
                  if (rest > head.size) head :: loop(rest - head.size, tail)
                  else List(GroupRange(head.start, Math.min(1d, head.start + rest)))
                case Nil => throw new Exception(s"Something went terribly wrong, do not have enough available slot to assign $rest")
              }
          }
          loop(neededSize, available.sortBy(-_.size))
        }

        val newAssignment =
          assigned.get(group.name).fold(allocate(group.size, allAvailable)) { assignment =>
            val assignedSize = assignment.foldMap(_.size)
            if (assignedSize > group.size) {
              allocate(group.size, assignment) //allocate from the existing assignment
            } else
              assignment ++ allocate(group.size - assignedSize, allAvailable)
          }

        assigned + (group.name -> consolidateRanges(newAssignment))
      }

    }
  }

}
