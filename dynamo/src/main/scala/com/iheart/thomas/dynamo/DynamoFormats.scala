package com.iheart.thomas
package dynamo

import java.time.Instant

import com.iheart.thomas.analysis.{Conversions, Probability}
import org.scanamo.DynamoFormat
import io.estatico.newtype.ops._
import com.iheart.thomas.bandit.bayesian._

object DynamoFormats {

  import org.scanamo.semiauto._

  implicit val dfProbability: DynamoFormat[Probability] =
    DynamoFormat[Double].coerce[DynamoFormat[Probability]]
  implicit val dfInstant: DynamoFormat[Instant] =
    DynamoFormat.coercedXmap(Instant.ofEpochMilli)(_.toEpochMilli)
  implicit val dfc: DynamoFormat[BanditState[Conversions]] =
    deriveDynamoFormat[BanditState[Conversions]]
}
