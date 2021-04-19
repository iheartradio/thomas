package com.iheart.thomas.stream

import com.iheart.thomas.analysis.{KPI, KPIName}
import com.iheart.thomas.stream.JobSpec.UpdateKPIPrior
import fs2.Pipe

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

trait KPIJobAlg[F[_], K <: KPI, Message] {
  def run(updateKPIPrior: UpdateKPIPrior): F[Pipe[F, Message, Unit]]

}

object KPIJobAlg {
  case class Settings(processFrequency: FiniteDuration, )
}
