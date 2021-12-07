package com.iheart.thomas.bandit
package bayesian

import com.iheart.thomas.{FeatureName, GroupName}
import com.iheart.thomas.analysis.KPIName
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{Key, Specialization}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** @param feature
  * @param title
  * @param author
  * @param kpiName
  * @param minimumSizeChange
  *   the minimum threshold of group size change. to avoid small fluctuation on
  *   statistics change
  * @param initialSampleSize
  *   the sample size from which the allocation starts.
  * @param historyRetention
  *   for how long retired ab test versions are being kept
  * @param maintainExplorationSize
  * @param reservedGroups
  *   reserve some arms from being changed by the bandit alg (useful for A/B tests)
  */
case class BanditSpec(
    feature:
    FeatureName,
    title: String,
    author: String,
    kpiName: KPIName,
    arms: Seq[ArmSpec],
    minimumSizeChange: Double = 0.001,
    historyRetention: Option[FiniteDuration] = None,
    initialSampleSize: Int = 0,
    stateMonitorEventChunkSize: Int = 1000,
    stateMonitorFrequency: FiniteDuration = 1.minute,
    updatePolicyStateChunkSize: Int = 100,
    updatePolicyFrequency: FiniteDuration = 1.hour) {
  lazy val stateKey: Key = Key(feature, kpiName, Specialization.BanditCurrent)

  lazy val reservedGroups: Set[GroupName] = arms.filter(_.reserved).map(_.name).toSet
}

private[thomas] trait BanditSpecDAO[F[_]] {
  def insert(
      state: BanditSpec
    ): F[BanditSpec]

  def remove(featureName: FeatureName): F[Unit]

  def get(featureName: FeatureName): F[BanditSpec]

  def update(
      settings: BanditSpec
    ): F[BanditSpec]
}
