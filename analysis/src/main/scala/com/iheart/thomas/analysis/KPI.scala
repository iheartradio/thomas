package com.iheart.thomas.analysis

import com.iheart.thomas.analysis.DistributionSpec.Normal

import monocle.macros.syntax.lens._

sealed trait KPI extends Serializable with Product {
  def name: KPIName
}

case class GammaKPI(name: KPIName,
                    shapePrior: Normal,
                    scalePrior: Normal) extends KPI {
  def scalePriors(by: Double): GammaKPI = {
    val g = this.lens(_.shapePrior.scale).modify(_ * by)
    g.lens(_.scalePrior.scale).modify(_ * by)
  }
}


object GammaKPI {
  import play.api.libs.json.Json.WithDefaultValues
  import play.api.libs.json._


  private val j = Json.using[WithDefaultValues]

  implicit val normalDistFormat = j.format[Normal]

  implicit val gammpKPIFormat = j.format[GammaKPI]

}
