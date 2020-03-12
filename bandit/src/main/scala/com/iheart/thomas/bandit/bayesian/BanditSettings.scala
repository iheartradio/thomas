package com.iheart.thomas.bandit.bayesian

import com.iheart.thomas.FeatureName
import com.iheart.thomas.analysis.KPIName

import scala.concurrent.duration.FiniteDuration

case class BanditSettings[SpecificSettings](
    feature: FeatureName,
    title: String,
    author: String,
    kpiName: KPIName,
    minimumSizeChange: Double,
    historyRetention: Option[FiniteDuration] = None,
    initialSampleSize: Int = 0,
    distSpecificSettings: SpecificSettings)

object BanditSettings {

  case class Conversion(
      eventChunkSize: Int,
      reallocateEveryNChunk: Int)
}
