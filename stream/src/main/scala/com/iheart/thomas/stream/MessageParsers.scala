package com.iheart.thomas
package stream

import cats.MonadThrow
import com.iheart.thomas.analysis._
import cats.implicits._
import org.typelevel.jawn.ast.{JNull, JValue}

import java.time.Instant
import scala.annotation.implicitNotFound
import scala.util.control.NoStackTrace
import scala.util.matching.Regex

@implicitNotFound(
  "Need to provide a parse that can parse group name (or arm name) out of ${Message} of event "
)
trait ArmParser[F[_], Message] {
  def parse(
      m: Message,
      feature: FeatureName
    ): F[Option[ArmName]]
}

object ArmParser {
  type JValueArmParser[F[_]] = ArmParser[F, JValue]
}

trait TimeStampParser[F[_], Message] {
  def parse(
      m: Message
    ): F[Instant]
}

trait KpiEventParser[F[_], Message, Event, K <: KPI] {
  def apply(k: K): Message => F[List[Event]]
}

object KpiEventParser {
  case class NoEventQueryForKPI(kpiName: KPIName)
      extends RuntimeException
      with NoStackTrace

  private[stream] def parseConversionEvent(
      json: JValue,
      query: ConversionMessageQuery
    ) = {
    import JValueSyntax._
    (if (json.filterAnd(query.initMessage.criteria: _*).nonNull)
       List(Initiated)
     else
       Nil) ++
      (if (json.filterAnd(query.convertedMessage.criteria: _*).nonNull)
         Converted.some
       else
         Nil)
  }

  implicit def jValueConversionEventParser[
      F[_]: MonadThrow
    ]: KpiEventParser[F, JValue, ConversionEvent, ConversionKPI] =
    (kpi: ConversionKPI) => {
      (json: JValue) =>
        kpi.messageQuery
          .liftTo[F](NoEventQueryForKPI(kpi.name))
          .map(parseConversionEvent(json, _))
    }

}

object JValueSyntax {

  implicit class jValueSyntaxExtension(private val jv: JValue) extends AnyVal {

    def getPath(path: String): JValue =
      path.split('.').foldLeft(jv)((j, k) => j.get(k))

    def filter(
        path: String,
        regex: Regex
      ): JValue =
      getPath(path).getString
        .filter(s => regex.findFirstMatchIn(s).isDefined)
        .as(jv)
        .getOrElse(JNull)

    def filterAnd(crit: Criteria*): JValue =
      crit.foldLeft(jv)((m, c) => m.filter(c.fieldName, c.regex))
  }
}
