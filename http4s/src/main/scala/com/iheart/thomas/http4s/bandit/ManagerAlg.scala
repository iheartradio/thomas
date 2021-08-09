package com.iheart.thomas.http4s.bandit

import cats.Monad
import com.iheart.thomas.FeatureName
import com.iheart.thomas.bandit.BanditStatus
import com.iheart.thomas.bandit.bayesian.{BanditSpec, BayesianMABAlg}
import com.iheart.thomas.stream.{Job, JobAlg}
import com.iheart.thomas.stream.JobSpec.RunBandit
import cats.implicits._

trait ManagerAlg[F[_]] {
  def status(feature: FeatureName): F[BanditStatus]
  def pause(feature: FeatureName): F[Unit]
  def start(feature: FeatureName): F[Option[Job]]
  def create(bs: BanditSpec): F[Bandit]
  def allBandits: F[Seq[Bandit]]
  def get(feature: FeatureName): F[Bandit]
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

    def allBandits: F[Seq[Bandit]] =
      alg.getAll.flatMap(_.traverse(b => status(b.feature).map(Bandit(b, _)))).widen

    def create(bs: BanditSpec): F[Bandit] =
      alg.init(bs).map(Bandit(_, BanditStatus.Paused))

    def get(feature: FeatureName): F[Bandit] =
      (alg.get(feature), status(feature)).mapN(Bandit.apply)
  }
}
