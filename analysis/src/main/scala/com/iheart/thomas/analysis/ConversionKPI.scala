package com.iheart.thomas.analysis
import MessageQuery._
import cats.{FlatMap, MonadThrow, UnorderedFoldable}
import cats.implicits._

import scala.util.control.NoStackTrace
import scala.util.matching.Regex

case class ConversionKPI(
    name: KPIName,
    author: String,
    description: Option[String],
    model: BetaModel,
    messageQuery: Option[ConversionMessageQuery])

case class BetaModel(
    alphaPrior: Double,
    betaPrior: Double) {
  def updateFrom(conversions: Conversions): BetaModel =
    copy(
      alphaPrior = conversions.converted + 1d,
      betaPrior = conversions.total - conversions.converted + 1d
    )

  def accumulativeUpdate(
      c: Conversions
    ): BetaModel = {
    copy(
      alphaPrior = alphaPrior + c.converted.toDouble,
      betaPrior = betaPrior + c.total.toDouble - c.converted.toDouble
    )
  }
}

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

trait ConversionKPIAlg[F[_]] {

  def create(
      kpi: ConversionKPI
    )(implicit F: MonadThrow[F]
    ): F[ConversionKPI] =
    if (!kpi.name.n.matches("[-_.A-Za-z0-9\\s]+"))
      F.raiseError(InvalidKPIName)
    else if (kpi.model.betaPrior < 0 || kpi.model.alphaPrior < 0)
      F.raiseError(InvalidModelPrior)
    else
      insert(kpi)

  protected def insert(conversionKPI: ConversionKPI): F[ConversionKPI]

  def update(conversionKPI: ConversionKPI): F[ConversionKPI]

  def remove(name: KPIName): F[Unit]

  def find(name: KPIName): F[Option[ConversionKPI]]

  def all: F[Vector[ConversionKPI]]

  def get(name: KPIName): F[ConversionKPI]

  def updateModel(
      name: KPIName
    )(update: BetaModel => BetaModel
    )(implicit F: FlatMap[F]
    ): F[ConversionKPI] =
    get(name).flatMap { kpi =>
      setModel(name, update(kpi.model))
    }

  def updateModel[C[_]: UnorderedFoldable](
      name: KPIName,
      events: C[ConversionEvent]
    )(implicit F: FlatMap[F]
    ): F[ConversionKPI] =
    updateModel(name) { m =>
      m.accumulativeUpdate(Conversions(events))
    }

  def setModel(
      name: KPIName,
      model: BetaModel
    ): F[ConversionKPI]

}

case object InvalidKPIName extends RuntimeException with NoStackTrace
case object InvalidModelPrior extends RuntimeException with NoStackTrace
