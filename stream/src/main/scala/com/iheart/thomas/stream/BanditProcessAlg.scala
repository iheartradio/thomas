package com.iheart.thomas.stream

import cats.effect.Temporal
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
  implicit def default[F[_]: Temporal, Message](
      implicit allKPIProcessAlg: AllKPIProcessAlg[F, Message],
      banditAlg: BayesianMABAlg[F]
    ): BanditProcessAlg[F, Message] = new BanditProcessAlg[F, Message] {

    def process(
        feature: FeatureName
      ): F[(Pipe[F, Message, Unit], ProcessSettings)] = {
      for {
        bandit <- banditAlg.get(feature)
        settings = ProcessSettings(
          bandit.spec.stateMonitorFrequency,
          bandit.spec.stateMonitorEventChunkSize,
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
                bandit.spec.updatePolicyStateChunkSize,
                bandit.spec.updatePolicyFrequency
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
