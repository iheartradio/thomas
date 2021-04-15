package com.iheart.thomas.analysis
import MessageQuery._
import bayesian.models.{BetaModel, LogNormalModel}

import scala.concurrent.duration.FiniteDuration
import scala.util.matching.Regex

sealed trait KPI {
  def name: KPIName
  def author: String
  def description: Option[String]
}

case class AccumulativeKPI(
    name: KPIName,
    author: String,
    description: Option[String],
    model: LogNormalModel,
    period: FiniteDuration,
    valueQuery: Option[MessageQuery])
    extends KPI

case class ConversionKPI(
    name: KPIName,
    author: String,
    description: Option[String],
    model: BetaModel,
    messageQuery: Option[ConversionMessageQuery])
    extends KPI

case class ConversionMessageQuery(
    initMessage: MessageQuery,
    convertedMessage: MessageQuery)

case class MessageQuery(
    description: Option[String],
    criteria: List[Criteria])

case class Criteria(
    fieldName: FieldName,
    matchingRegex: FieldRegex) {
  lazy val regex: Regex = new Regex(matchingRegex)
}

object MessageQuery {
  type FieldName = String
  type FieldRegex = String
}
