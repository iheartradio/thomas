package com.iheart.thomas
package monitor

import play.api.libs.json._

case class Event(
    title: String,
    text: String,
    status: Status.Status,
    tags: List[String])

object Event {
  type Status = Status.Status
  val Status = com.iheart.thomas.monitor.Status
  implicit val format: Writes[Event] = Json.writes[Event]
}

private[monitor] object Status extends Enumeration {
  type Status = Value

  val success, error = Value

  implicit val format: Format[Status] = Json.formatEnum(this)
}
