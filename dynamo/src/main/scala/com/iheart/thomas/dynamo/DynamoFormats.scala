package com.iheart.thomas
package dynamo

import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

import com.iheart.thomas.admin.{AuthRecord, Role, User}
import com.iheart.thomas.analysis.{Conversions, KPIName, Probability}
import org.scanamo.DynamoFormat
import io.estatico.newtype.ops._
import com.iheart.thomas.bandit.bayesian._

import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration

object DynamoFormats {

  import org.scanamo.generic.semiauto._

  implicit val dfProbability: DynamoFormat[Probability] =
    DynamoFormat[Double].coerce[DynamoFormat[Probability]]

  implicit val dfKPName: DynamoFormat[KPIName] =
    DynamoFormat[String].coerce[DynamoFormat[KPIName]]

  implicit val dfOffsetTime: DynamoFormat[OffsetDateTime] =
    DynamoFormat
      .coercedXmap[OffsetDateTime, String, DateTimeParseException](
        (s: String) =>
          OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
      )(
        _.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
      )

  implicit val conversionsSfc: DynamoFormat[Conversions] =
    deriveDynamoFormat[Conversions]
  implicit val armSfc: DynamoFormat[ArmState[Conversions]] =
    deriveDynamoFormat[ArmState[Conversions]]

  implicit val dfc: DynamoFormat[BanditState[Conversions]] =
    deriveDynamoFormat[BanditState[Conversions]]

  implicit val fddf: DynamoFormat[duration.FiniteDuration] =
    DynamoFormat.coercedXmap[FiniteDuration, Long, Throwable](
      FiniteDuration(_, TimeUnit.NANOSECONDS)
    )(_.toNanos)

  implicit val bss: DynamoFormat[BanditSettings[BanditSettings.Conversion]] =
    deriveDynamoFormat[BanditSettings[BanditSettings.Conversion]]

  implicit val authRecordFormat: DynamoFormat[AuthRecord] =
    deriveDynamoFormat[AuthRecord]

  implicit val roleFormat: DynamoFormat[Role] =
    deriveDynamoFormat[Role]

  implicit val userFormat: DynamoFormat[User] =
    deriveDynamoFormat[User]
}
