package com.iheart.thomas.stream

import cats.MonadThrow
import cats.effect.{Timer, Concurrent}
import com.iheart.thomas.FeatureName
import com.iheart.thomas.bandit.bayesian.BayesianMABAlg
import com.iheart.thomas.stream.JobSpec.ProcessSettings
import fs2.Pipe
import cats.implicits._
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.Specialization

trait BanditProcessAlg[F[_], Message] {
  def process(
      feature: FeatureName
    ): F[(Pipe[F, Message, Unit], ProcessSettings)]
}

object BanditProcessAlg {
  implicit def default[F[_]: MonadThrow: Timer: Concurrent, Message](
      implicit allKPIProcessAlg: AllKPIProcessAlg[F, Message],
      banditAlg: BayesianMABAlg[F]
    ): BanditProcessAlg[F, Message] = new BanditProcessAlg[F, Message] {

    def process(
        feature: FeatureName
      ): F[(Pipe[F, Message, Unit], ProcessSettings)] = {
      for {
        bandit <- banditAlg.get(feature)
        settings = ProcessSettings(
          bandit.settings.stateMonitorFrequency,
          bandit.settings.stateMonitorEventChunkSize,
          None
        )
        monitorPipe <- allKPIProcessAlg.monitorExperiment(
          feature,
          bandit.kpiName,
          Specialization.BanditCurrent,
          settings
        )
      } yield {
        (
          monitorPipe.andThen { states =>
            states
              .groupWithin(
                bandit.settings.updatePolicyEveryNStateUpdate,
                bandit.settings.updatePolicyFrequency
              )
              .evalMapFilter(_.last.traverse(banditAlg.updatePolicy))
              .void
          },
          settings
        )
      }
    }
  }
}
