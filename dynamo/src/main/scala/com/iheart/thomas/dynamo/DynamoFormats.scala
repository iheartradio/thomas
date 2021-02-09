package com.iheart.thomas
package dynamo

import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import com.iheart.thomas.admin.{AuthRecord, User}
import com.iheart.thomas.analysis._
import com.iheart.thomas.analysis.monitor.ExperimentKPIState
import org.scanamo.{DynamoFormat, TypeCoercionError}
import io.estatico.newtype.ops._
import com.iheart.thomas.bandit.bayesian._

import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration
import stream._

object DynamoFormats {

  import org.scanamo.generic.semiauto._

  implicit val dfProbability: DynamoFormat[Probability] =
    DynamoFormat[Double].coerce[DynamoFormat[Probability]]

  implicit val dfKPName: DynamoFormat[KPIName] =
    DynamoFormat[String].coerce[DynamoFormat[KPIName]]

  implicit val dfOffsetTime: DynamoFormat[OffsetDateTime] =
    DynamoFormat
      .coercedXmap[OffsetDateTime, String, DateTimeParseException]((s: String) =>
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

  implicit val userFormat: DynamoFormat[User] =
    deriveDynamoFormat[User]

  implicit val betaFormat: DynamoFormat[BetaModel] = deriveDynamoFormat[BetaModel]

  implicit val conversionKPIFormat: DynamoFormat[ConversionKPI] =
    deriveDynamoFormat[ConversionKPI]

  implicit val jobFormat: DynamoFormat[Job] = deriveDynamoFormat[Job]

  implicit val estateKeyFormat: DynamoFormat[ExperimentKPIState.Key] =
    DynamoFormat.xmap[ExperimentKPIState.Key, String](s =>
      ExperimentKPIState
        .parseKey(s)
        .toRight(TypeCoercionError(new Exception("Invalid key format in DB: " + s)))
    )(_.toStringKey)

  implicit val armStateConversionFormat
      : DynamoFormat[ExperimentKPIState.ArmState[Conversions]] =
    deriveDynamoFormat[ExperimentKPIState.ArmState[Conversions]]

  implicit val ekpiStateConversionFormat
      : DynamoFormat[ExperimentKPIState[Conversions]] =
    deriveDynamoFormat[ExperimentKPIState[Conversions]]

}
