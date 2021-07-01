package com.iheart.thomas.bandit
package bayesian

import com.iheart.thomas.{FeatureName, GroupName}
import com.iheart.thomas.abtest.model.GroupSize
import com.iheart.thomas.analysis.KPIName
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{Key, Specialization}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** @param feature
  *   @param title
  * @param author
  *   @param kpiName
  * @param minimumSizeChange
  *   the minimum threshold of group size change. to avoid small fluctuation on
  *   statistics change
  * @param initialSampleSize
  *   the sample size from which the allocation starts.
  * @param iterationDuration
  *   bandit can evolve metrics statics by iteration,
  * i.e. retire old metrics data 2 iterations ago
  * @param oldHistoryWeight
  *   historical metrics data from 2 iterations ago can be kept with a weight to be
  *   combined with metrics from 1 iteration ago.
  * @param historyRetention
  *   @param maintainExplorationSize
  * @param reservedGroups
  *   reserve some arms from being changed by the bandit alg (useful for A/B tests)
  */
case class BanditSettings(
    feature: FeatureName,
    title: String,
    author: String,
    kpiName: KPIName,
    minimumSizeChange: Double = 0.001,
    historyRetention: Option[FiniteDuration] = None,
    initialSampleSize: Int = 0,
    maintainExplorationSize: Option[GroupSize] = None,
    iterationDuration: Option[FiniteDuration] = None, //todo: remove this
    oldHistoryWeight: Option[Weight] = None, //todo: remove this
    reservedGroups: Set[GroupName] = Set.empty,
    stateMonitorEventChunkSize: Int = 1000,
    stateMonitorFrequency: FiniteDuration = 1.minute,
    updatePolicyEveryNStateUpdate: Int = 100,
    updatePolicyFrequency: FiniteDuration = 1.hour) {
  lazy val stateKey: Key = Key(feature, kpiName, Specialization.BanditCurrent)
}
