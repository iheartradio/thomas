package com.iheart.thomas.bandit

import com.iheart.thomas.analysis.{Conversions, Probability}
import com.iheart.thomas.bandit.bayesian.{ArmState, BayesianState}
import play.api.libs.json.{Format, Json}
import io.estatico.newtype.ops._

object Formats {
  implicit val jfProbability: Format[Probability] =
    implicitly[Format[Double]].coerce[Format[Probability]]

  implicit val jfC: Format[Conversions] = Json.format[Conversions]
  implicit val jfAS: Format[ArmState[Conversions]] = Json.format[ArmState[Conversions]]
  implicit val jfBS: Format[BanditSpec] = Json.format[BanditSpec]
  implicit val jfBSC: Format[BayesianState[Conversions]] =
    Json.format[BayesianState[Conversions]]
}
