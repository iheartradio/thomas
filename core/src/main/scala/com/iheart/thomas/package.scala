package com.iheart

import cats.MonadError

package object thomas {
  type KPIValue = Double
  type FeatureName = String
  type GroupName = String
  type UserId = String
  type MonadThrowable[F[_]] = MonadError[F, Throwable]
}
