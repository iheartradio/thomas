package com.iheart.thomas
package bandit

import com.iheart.thomas.analysis.{Conversions, Probability}
import com.iheart.thomas.bandit.bayesian.{ArmState, BanditState, BayesianMAB}
import _root_.play.api.libs.json.{Format, Json}
import io.estatico.newtype.ops._
import lihua.playJson.Formats._
import com.iheart.thomas.abtest.Formats._
object Formats {
  implicit val jfProbability: Format[Probability] =
    implicitly[Format[Double]].coerce[Format[Probability]]

  implicit val jfC: Format[Conversions] = Json.format[Conversions]
  implicit val jfAS: Format[ArmState[Conversions]] =
    Json.format[ArmState[Conversions]]
  implicit val jfBS: Format[BanditSpec] = Json.format[BanditSpec]
  implicit val jfBSC: Format[BanditState[Conversions]] =
    Json.format[BanditState[Conversions]]

  implicit val jfBMAB: Format[BayesianMAB[Conversions]] =
    Json.format[BayesianMAB[Conversions]]

}
