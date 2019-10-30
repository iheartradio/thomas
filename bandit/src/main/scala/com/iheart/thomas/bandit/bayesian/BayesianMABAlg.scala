package com.iheart.thomas.bandit.bayesian

import java.time.OffsetDateTime

import cats.Monad
import cats.implicits._
import com.iheart.thomas.FeatureName
import com.iheart.thomas.bandit.BanditSpec
import com.iheart.thomas.bandit.`package`.ArmName

/**
  * Abtest based Bayesian Multi Arm Bandit Algebra
  * @tparam F
  * @tparam R
  */
trait BayesianMABAlg[F[_], R] {
  def updateRewardState(
      featureName: FeatureName,
      rewardState: Map[ArmName, R]
    ): F[BanditState[R]]

  def init(banditSpec: BanditSpec): F[BayesianMAB[R]]

  def currentState(featureName: FeatureName): F[BayesianMAB[R]]

  def runningBandits(time: Option[OffsetDateTime] = None): F[Vector[BayesianMAB[R]]]

  def reallocate(featureName: FeatureName): F[BayesianMAB[R]]

}

object BayesianMABAlg {
  implicit class BayesianMABAlgExtension[F[_]: Monad, R](
      private val alg: BayesianMABAlg[F, R]) {
    def reallocateAllRunning: F[Vector[BayesianMAB[R]]] =
      alg.runningBandits(None).flatMap { bandits =>
        bandits.traverse(b => alg.reallocate(b.feature))
      }
  }
}
