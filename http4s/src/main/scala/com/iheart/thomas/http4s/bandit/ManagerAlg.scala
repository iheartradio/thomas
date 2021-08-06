package com.iheart.thomas.http4s.bandit

import cats.Monad
import com.iheart.thomas.FeatureName
import com.iheart.thomas.bandit.BanditStatus
import com.iheart.thomas.bandit.bayesian.{BanditSpec, BayesianMAB, BayesianMABAlg}
import com.iheart.thomas.stream.{Job, JobAlg}
import com.iheart.thomas.stream.JobSpec.RunBandit
import cats.implicits._

trait ManagerAlg[F[_]] {
  def status(feature: FeatureName): F[BanditStatus]
  def pause(feature: FeatureName): F[Unit]
  def start(feature: FeatureName): F[Option[Job]]
  def create(bs: BanditSpec): F[BayesianMAB]
  def allBandits: F[Seq[(BayesianMAB, BanditStatus)]]
}

object ManagerAlg {
  implicit def apply[F[_]: Monad](
      implicit alg: BayesianMABAlg[F],
      jobAlg: JobAlg[F]
    ): ManagerAlg[F] = new ManagerAlg[F] {

    def status(feature: FeatureName): F[BanditStatus] = {
      jobAlg
        .find(RunBandit(feature))
        .map(_.fold(BanditStatus.Paused: BanditStatus)(_ => BanditStatus.Running))
    }

    def pause(feature: FeatureName): F[Unit] = jobAlg.stop(RunBandit(feature))

    def start(feature: FeatureName): F[Option[Job]] =
      jobAlg.schedule(RunBandit(feature))

    def allBandits: F[Seq[(BayesianMAB, BanditStatus)]] =
      alg.getAll.flatMap(_.traverse(b => status(b.feature).map((b, _)))).widen

    def create(bs: BanditSpec): F[BayesianMAB] = alg.init(bs)
  }
}
