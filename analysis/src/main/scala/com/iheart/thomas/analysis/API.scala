package com.iheart.thomas

package analysis

import java.time.OffsetDateTime

import model.{FeatureName, GroupName}


trait API[F[_]] {
  def assess(featureName: FeatureName,
             kPIName: KPIName,
             baselineGroup: GroupName,
             days: Option[Int] = None
            ): F[Map[GroupName, NumericGroupResult]]

  def updateKPIDistribution(
    KPIName: KPIName,
    start: OffsetDateTime,
    end: OffsetDateTime): F[(KPIDistribution, Double)]
}

object API {
  implicit def default[F[_]](implicit
                             measurable: AbtestMeasurable[F, GammaKPIDistribution],

                            ) = new API[F] {
    override def assess(featureName: FeatureName, kPIName: KPIName, baselineGroup: GroupName, days: Option[Int]): F[Map[GroupName, NumericGroupResult]] = ???

    override def updateKPIDistribution(KPIName: KPIName, start: OffsetDateTime, end: OffsetDateTime): F[(KPIDistribution, Double)] = ???
  }
}
