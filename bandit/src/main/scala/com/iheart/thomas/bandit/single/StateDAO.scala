package com.iheart.thomas.bandit.single

import com.iheart.thomas.FeatureName

trait StateDAO[F[_], BS] {
  def upsert(state: BS): F[BS]
  def update(state: BS): F[BS]
  def remove(featureName: FeatureName): F[Unit]

  def get(featureName: FeatureName): F[BS]
}
