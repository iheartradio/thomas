package com.iheart.thomas.analysis
import MessageQuery._

case class ConversionKPI(
    name: KPIName,
    author: String,
    description: Option[String],
    model: BetaModel,
    messageQuery: Option[ConversionMessageQuery])

case class BetaModel(
    alphaPrior: Double,
    betaPrior: Double)

case class ConversionMessageQuery(
    initMessage: MessageQuery,
    convertedMessage: MessageQuery)

case class MessageQuery(
    description: Option[String],
    criteria: List[(FieldName, FieldValue)])

object MessageQuery {
  type FieldName = String
  type FieldValue = String
}

trait ConversionKPIDAO[F[_]] {
  def upsert(conversionKPI: ConversionKPI): F[ConversionKPI]

  def remove(name: KPIName): F[Unit]

  def find(name: KPIName): F[Option[ConversionKPI]]

  def all: F[Vector[ConversionKPI]]

  def get(name: KPIName): F[ConversionKPI]

}
