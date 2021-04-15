package com.iheart.thomas
package stream

import cats.MonadThrow
import com.iheart.thomas.analysis._
import cats.implicits._
import org.typelevel.jawn.ast.{JNull, JValue}

import scala.annotation.implicitNotFound
import scala.util.control.NoStackTrace
import scala.util.matching.Regex

@implicitNotFound(
  "Need to provide a parse that can parse group name (or arm name) out of ${Message} of event "
)
trait ArmParser[F[_], Message] {
  def parseArm(
      m: Message,
      feature: FeatureName
    ): F[Option[ArmName]]
}

trait KpiEventParser[F[_], Message, Event] {
  def apply(kpiName: KPIName): F[Message => F[List[Event]]]
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

  implicit def jValueConversionEventParser[F[_]: MonadThrow](
      implicit kpiRepo: KPIRepo[F, ConversionKPI]
    ): KpiEventParser[F, JValue, ConversionEvent] =
    (kpiName: KPIName) =>
      kpiRepo
        .find(kpiName)
        .flatMap(
          _.flatMap(_.messageQuery).liftTo[F](NoEventQueryForKPI(kpiName))
        )
        .map(q => (json: JValue) => parseConversionEvent(json, q).pure[F])
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
