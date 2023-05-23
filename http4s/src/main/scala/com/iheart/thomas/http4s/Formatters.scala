package com.iheart.thomas.http4s

import java.time.{Instant, OffsetDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import com.iheart.thomas.abtest.model.{Abtest, GroupSize, UserMetaCriterion}
import com.iheart.thomas.abtest.model.Abtest.Status
import lihua.Entity
import _root_.play.api.libs.json.{JsObject, Json, Writes}
import com.iheart.thomas.abtest.json.play.Formats._

object Formatters {
  val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
  val dateTimeFormatterShort = DateTimeFormatter.ofPattern("MMM dd HH:mm")
  val dateTimeFormatterMid = DateTimeFormatter.ofPattern("M/d/yy HH:mm")

  def formatPercentage(d: Double): String = f"${d * 100}%.2f%%"

  def formatDate(date: OffsetDateTime): String =
    formatDate(date.toInstant)

  def dateTimeMid(instant: Instant): String =
    formatDate(instant, dateTimeFormatterMid)

  def formatDate(
      date: Instant,
      formatter: DateTimeFormatter = dateTimeFormatter
    ): String = {
    date.atZone(ZoneId.systemDefault).format(formatter)
  }

  def formatJSON[A](a: A)(implicit w: Writes[A]): String = {
    Json.prettyPrint(w.writes(a))
  }

  //writes all criteria in c into json objects and merge them into one.
  def formatUserMetaCriteria(c: UserMetaCriterion.And): String = {
    val jsArray = userMetaCriteriaFormat.writes(c)
    val jsObject = jsArray.as[List[JsObject]].reduceLeft(_ deepMerge _)

    Json.prettyPrint(
      if(c.criteria.size > jsObject.fields.size) Json.obj("%and" -> jsArray) else jsObject
    )
  }

  def formatArmSize(size: GroupSize): String = {
    "%.3f".format(size)
  }

  def formatStatus(test: Entity[Abtest]): (String, String) = {
    test.data.statusAsOf(OffsetDateTime.now) match {
      case Status.Expired    => ("Stopped", "secondary")
      case Status.InProgress => ("Running", "success")
      case Status.Scheduled  => ("Scheduled", "warning")
    }
  }

}
