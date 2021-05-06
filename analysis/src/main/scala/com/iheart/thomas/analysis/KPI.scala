package com.iheart.thomas.analysis
import MessageQuery._
import bayesian.models.{BetaModel, LogNormalModel}

import scala.util.matching.Regex

sealed trait KPI {
  def name: KPIName
  def author: String
  def description: Option[String]
}

sealed trait AccumulativeKPI extends KPI {
  def model: LogNormalModel
}

case class QueryAccumulativeKPI(
    name: KPIName,
    author: String,
    description: Option[String],
    model: LogNormalModel,
    queryName: QueryName,
    queryParams: Map[String, String])
    extends AccumulativeKPI

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
