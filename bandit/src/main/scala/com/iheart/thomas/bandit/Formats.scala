package com.iheart.thomas
package bandit

import com.iheart.thomas.analysis.{Conversions, KPIName, Probability}
import com.iheart.thomas.bandit.bayesian._
import _root_.play.api.libs.json.{Format, Json}
import io.estatico.newtype.ops._
import lihua.playJson.Formats._
import com.iheart.thomas.abtest.json.play.Formats._
import play.api.libs.json.Json.WithDefaultValues
object Formats {
  val j = Json.using[WithDefaultValues]

  implicit val jfProbability: Format[Probability] =
    implicitly[Format[Double]].coerce[Format[Probability]]

  implicit val jfKPIName: Format[KPIName] =
    implicitly[Format[String]].coerce[Format[KPIName]]

  implicit val jfC: Format[Conversions] = j.format[Conversions]

  implicit val jfBC: Format[BanditSettings.Conversion] =
    j.format[BanditSettings.Conversion]

  implicit val jfBSWC: Format[BanditSettings[BanditSettings.Conversion]] =
    j.format[BanditSettings[BanditSettings.Conversion]]

  implicit val jfAS: Format[ArmState[Conversions]] =
    j.format[ArmState[Conversions]]

  implicit val jfBS: Format[ConversionBanditSpec] =
    j.format[BanditSpec[BanditSettings.Conversion]]

  implicit val jfBSC: Format[BanditState[Conversions]] =
    j.format[BanditState[Conversions]]

  implicit val jfBMAB: Format[ConversionBandit] =
    j.format[BayesianMAB[Conversions, BanditSettings.Conversion]]

}
