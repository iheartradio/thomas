package com.iheart.thomas
package dynamo

import com.iheart.thomas.admin.{AuthRecord, PassResetToken, Role, User}
import com.iheart.thomas.analysis._
import com.iheart.thomas.analysis.bayesian.models._
import com.iheart.thomas.analysis.monitor.ExperimentKPIState
import com.iheart.thomas.bandit.bayesian._
import com.iheart.thomas.stream.JobSpec.{ProcessSettingsOptional}
import com.iheart.thomas.stream._
import io.estatico.newtype.ops._
import org.scanamo.{DynamoFormat, TypeCoercionError}

import java.time.OffsetDateTime
import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.util.concurrent.TimeUnit
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
          OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        _.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
      )

  implicit val conversionsSfc: DynamoFormat[Conversions] =
    deriveDynamoFormat[Conversions]

  implicit val userSamplesSummarySfc: DynamoFormat[PerUserSamplesSummary] =
    deriveDynamoFormat[PerUserSamplesSummary]

  implicit val armSfc: DynamoFormat[ArmState[Conversions]] =
    deriveDynamoFormat[ArmState[Conversions]]

  implicit val armPUS: DynamoFormat[ArmState[PerUserSamplesSummary]] =
    deriveDynamoFormat[ArmState[PerUserSamplesSummary]]

  implicit val dfc: DynamoFormat[BanditState[Conversions]] =
    deriveDynamoFormat[BanditState[Conversions]]

  implicit val fddf: DynamoFormat[duration.FiniteDuration] =
    DynamoFormat.coercedXmap[FiniteDuration, Long, Throwable](
      FiniteDuration(_, TimeUnit.NANOSECONDS),
      _.toNanos
    )

  implicit val bssc: DynamoFormat[BanditSettings.Conversion] =
    deriveDynamoFormat[BanditSettings.Conversion]

  implicit val bss: DynamoFormat[BanditSettings[BanditSettings.Conversion]] =
    deriveDynamoFormat[BanditSettings[BanditSettings.Conversion]]

  implicit val authRecordFormat: DynamoFormat[AuthRecord] =
    deriveDynamoFormat[AuthRecord]

  implicit val roleFormat: DynamoFormat[Role] =
    deriveDynamoFormat[Role]

  implicit val passResetTokenFormat: DynamoFormat[PassResetToken] =
    deriveDynamoFormat[PassResetToken]

  implicit val userFormat: DynamoFormat[User] =
    deriveDynamoFormat[User]

  implicit val betaFormat: DynamoFormat[BetaModel] = deriveDynamoFormat[BetaModel]

  implicit val normalFormat: DynamoFormat[NormalModel] =
    deriveDynamoFormat[NormalModel]

  implicit val logNormalFormat: DynamoFormat[LogNormalModel] =
    deriveDynamoFormat[LogNormalModel]

  implicit val critFormat: DynamoFormat[Criteria] =
    deriveDynamoFormat[Criteria]

  implicit val mqFormat: DynamoFormat[MessageQuery] =
    deriveDynamoFormat[MessageQuery]

  implicit val cmqFormat: DynamoFormat[ConversionMessageQuery] =
    deriveDynamoFormat[ConversionMessageQuery]

  implicit val conversionKPIFormat: DynamoFormat[ConversionKPI] =
    deriveDynamoFormat[ConversionKPI]

  implicit val accumulativeKPIFormat: DynamoFormat[AccumulativeKPI] =
    deriveDynamoFormat[AccumulativeKPI]

  implicit val kPIFormat: DynamoFormat[KPI] =
    deriveDynamoFormat[KPI]

  implicit val processSettingsDF: DynamoFormat[ProcessSettingsOptional] =
    deriveDynamoFormat[ProcessSettingsOptional]

  implicit val jobSpecFormat: DynamoFormat[JobSpec] = deriveDynamoFormat[JobSpec]
  implicit val jobFormat: DynamoFormat[Job] = deriveDynamoFormat[Job]

  implicit val estateKeyFormat: DynamoFormat[ExperimentKPIState.Key] =
    DynamoFormat.xmap[ExperimentKPIState.Key, String](
      s =>
        ExperimentKPIState
          .parseKey(s)
          .toRight(
            TypeCoercionError(new Exception("Invalid key format in DB: " + s))
          ),
      _.toStringKey
    )

  implicit val armStateConversionFormat
      : DynamoFormat[ExperimentKPIState.ArmState[Conversions]] =
    deriveDynamoFormat[ExperimentKPIState.ArmState[Conversions]]

  implicit val ekpiStateConversionFormat
      : DynamoFormat[ExperimentKPIState[Conversions]] =
    deriveDynamoFormat[ExperimentKPIState[Conversions]]

  implicit val armStatePerUserSamplesFormat
      : DynamoFormat[ExperimentKPIState.ArmState[PerUserSamplesSummary]] =
    deriveDynamoFormat[ExperimentKPIState.ArmState[PerUserSamplesSummary]]

  implicit val ekpiStatePerUserSamplesFormat
      : DynamoFormat[ExperimentKPIState[PerUserSamplesSummary]] =
    deriveDynamoFormat[ExperimentKPIState[PerUserSamplesSummary]]

}
