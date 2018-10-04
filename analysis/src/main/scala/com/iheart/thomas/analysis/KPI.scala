package com.iheart.thomas.analysis

import com.iheart.thomas.analysis.DistributionSpec.Normal


sealed trait KPI {
  def name: KPIName
}

case class GammaKPI(name: KPIName,
                    shapePrior: Normal,
                    scalePrior: Normal) extends KPI

