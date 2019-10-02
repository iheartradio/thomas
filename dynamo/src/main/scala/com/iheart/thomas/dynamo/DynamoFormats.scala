package com.iheart.thomas
package dynamo

import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.time.OffsetDateTime

import com.iheart.thomas.analysis.{Conversions, Probability}
import org.scanamo.DynamoFormat
import io.estatico.newtype.ops._
import com.iheart.thomas.bandit.bayesian._

object DynamoFormats {

  import org.scanamo.semiauto._

  implicit val dfProbability: DynamoFormat[Probability] =
    DynamoFormat[Double].coerce[DynamoFormat[Probability]]

  implicit val dfOffsetTime: DynamoFormat[OffsetDateTime] =
    DynamoFormat
      .coercedXmap[OffsetDateTime, String, DateTimeParseException](
        (s: String) =>
          OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
      )(
        _.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
      )

  implicit val dfc: DynamoFormat[BanditState[Conversions]] =
    deriveDynamoFormat[BanditState[Conversions]]
}
