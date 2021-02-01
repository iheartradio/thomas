package com.iheart.thomas.stream

import cats.Applicative
import com.iheart.thomas.GroupName
import com.iheart.thomas.analysis.ConversionMessageQuery
import cats.implicits._
import org.typelevel.jawn.ast.{JNull, JValue}

trait GroupParser[F[_], Message] {
  def parseGroup(m: Message): F[GroupName]
}

trait ConversionParser[F[_], Message] {
  def parseConversion(
      m: Message,
      conversionMessageQuery: ConversionMessageQuery
    ): F[Option[ConversionEvent]]
}

object ConversionParser {

  implicit def jValueParser[F[_]: Applicative]: ConversionParser[F, JValue] =
    new ConversionParser[F, JValue] {
      def parseConversion(
          json: JValue,
          query: ConversionMessageQuery
        ): F[Option[ConversionEvent]] = {
        import JValueSyntax._
        if (json.filterAnd(query.initMessage.criteria: _*).nonNull)
          Viewed.some.pure[F]
        else if (json.filterAnd(query.convertedMessage.criteria: _*).nonNull)
          Converted.some.pure[F]
        else none[ConversionEvent].pure[F]
      }

    }

  object JValueSyntax {

    implicit class jValueSyntaxExtension(private val jv: JValue) extends AnyVal {

      def getPath(path: String): JValue =
        path.split('.').foldLeft(jv)((j, k) => j.get(k))

      def filter(
          path: String,
          value: String
        ): JValue =
        getPath(path).getString.filter(_ == value).as(jv).getOrElse(JNull)

      def filterAnd(crit: (String, String)*): JValue =
        crit.foldLeft(jv)((m, p) => m.filter(p._1, p._2))

    }
  }

}
