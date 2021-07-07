package com.iheart.thomas
package stream

import cats.Functor
import cats.effect.{Concurrent, Sync, Timer}
import cats.implicits._
import com.iheart.thomas.utils.time.InstantOps
import com.iheart.thomas.analysis.KPIName
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.Specialization
import com.iheart.thomas.stream.JobEvent.RunningJobsUpdated
import com.iheart.thomas.stream.JobSpec.{
  MonitorTest,
  ProcessSettings,
  ProcessSettingsOptional,
  RunBandit,
  UpdateKPIPrior
}
import com.iheart.thomas.tracking.EventLogger
import com.typesafe.config.Config
import fs2._
import pureconfig.ConfigSource

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.control.NoStackTrace
import pureconfig.generic.auto._
import PipeSyntax._

trait JobAlg[F[_]] {

  /** Creates a job if the job key is not already in the job list
    * @return
    *   Some(job) if successful, None otherwise.
    */
  def schedule(spec: JobSpec): F[Option[Job]]

  /** Stops and removes a job
    */
  def stop(jobKey: String): F[Unit]

  def runStream: Stream[F, Unit]

  def allJobs: F[Vector[Job]]

  def findInfo[JS <: JobSpec: ClassTag](
      condition: JS => Boolean
    ): F[Vector[JobInfo[JS]]]

  def findInfo[JS <: JobSpec: ClassTag](
      key: String
    )(implicit F: Functor[F]
    ): F[Option[JobInfo[JS]]] =
    findInfo[JS]((_: JS).key == key).map(_.headOption)

  def monitors(feature: FeatureName): F[Vector[JobInfo[MonitorTest]]] =
    findInfo((m: MonitorTest) => m.feature == feature)

  /** Find job according to spec. Since you can't run two jobs with the same key,
    * find ignores the rest of the spec.
    * @param spec
    *   @return
    */
  def find(spec: JobSpec): F[Option[Job]]

}

object JobAlg {
  def chunkEvents[F[_]: Timer: Concurrent, E](
      processSettings: ProcessSettings
    ): Pipe[F, E, Chunk[E]] =
    (input: Stream[F, E]) =>
      input
        .groupWithin(
          processSettings.eventChunkSize,
          processSettings.frequency
        )

  case class ConversionEventStats(
      initCount: Int,
      convertedCount: Int)

  case class CannotUpdateKPIWithoutQuery(kpiName: KPIName)
      extends RuntimeException
      with NoStackTrace

  implicit def apply[F[_], Message](
      implicit F: Concurrent[F],
      timer: Timer[F],
      dao: JobDAO[F],
      kpiPipes: AllKPIProcessAlg[F, Message],
      banditProcessAlg: BanditProcessAlg[F, Message],
      config: Config,
      logger: EventLogger[F],
      messageSubscriber: MessageSubscriber[F, Message]
    ): JobAlg[F] =
    new JobAlg[F] {
      def schedule(spec: JobSpec): F[Option[Job]] = dao.insertO(Job(spec))

      def stop(jobKey: String): F[Unit] = dao.remove(jobKey)

      def find(spec: JobSpec): F[Option[Job]] = dao.find(spec.key)

      def findInfo[JS <: JobSpec: ClassTag](
          condition: JS => Boolean
        ): F[Vector[JobInfo[JS]]] =
        allJobs.map(_.collect {
          case Job(_, js: JS, _, started) if (condition(js)) => JobInfo(started, js)
        })

      def allJobs: F[Vector[Job]] = dao.all

      def runStream: Stream[F, Unit] =
        Stream
          .eval(JobRunnerConfig.fromConfig(config))
          .flatMap { cfg =>
            def jobPipe(job: Job): F[Pipe[F, Message, Unit]] = {
              def processSettings(settings: ProcessSettingsOptional) = (
                ProcessSettings(
                  frequency = settings.frequency
                    .getOrElse(cfg.jobProcessFrequency),
                  eventChunkSize = settings.eventChunkSize
                    .getOrElse(cfg.maxChunkSize),
                  expiration = settings.expiration
                )
              )

              def checkExpiration[A](settings: ProcessSettings): Pipe[F, A, A] =
                (input: Stream[F, A]) => {
                  def checkJobComplete(exp: Instant) =
                    Stream
                      .fixedDelay(cfg.jobCheckFrequency)
                      .evalMap(_ => exp.passed)
                      .evalTap { completed =>
                        if (completed) stop(job.key)
                        else F.unit
                      }
                  settings.expiration.fold(input)(exp =>
                    input
                      .interruptWhen(checkJobComplete(exp))
                  )
                }

              (job.spec match {
                case UpdateKPIPrior(kpiName, s) =>
                  val settings = processSettings(s)
                  kpiPipes.updatePrior(kpiName, settings).map((_, settings))
                case MonitorTest(feature, kpiName, s) =>
                  val settings = processSettings(s)
                  kpiPipes
                    .monitorExperiment(
                      feature,
                      kpiName,
                      Specialization.RealtimeMonitor,
                      settings
                    )
                    .map(p => (p.void, settings))
                case RunBandit(fn) =>
                  banditProcessAlg.process(fn)
              }).map { case (pipe, settings) =>
                checkExpiration[Message](settings).andThen(pipe)
              }
            }

            val availableJobs: Stream[F, Vector[Job]] =
              Stream
                .fixedDelay[F](cfg.jobCheckFrequency)
                .evalMap(_ =>
                  utils.time.now[F].flatMap { now =>
                    dao.all.map(_.filter { j =>
                      j.checkedOut.fold(true)(lastCheckedOut =>
                        now.isAfter(
                          lastCheckedOut.plusDuration(
                            cfg.jobCheckFrequency * cfg.jobObsoleteCount
                          )
                        )
                      )
                    })
                  }
                ) //todo: be resilient against DB error with logging.

            val runningJobs: Stream[F, Vector[Job]] = availableJobs
              .evalScan(
                (
                  Vector.empty[Job], //previous set of Jobs
                  none[Vector[
                    Job
                  ]] // current Job, None if no change from previous bandits
                )
              ) { (memo, newAvailable) =>
                val currentlyRunning = memo._1
                for {
                  now <- utils.time.now[F]
                  updatedRunning <-
                    currentlyRunning.traverseFilter(dao.updateCheckedOut(_, now))
                  newlyCheckedout <-
                    newAvailable.traverseFilter(dao.updateCheckedOut(_, now))
                  newlyStarted <- newlyCheckedout.traverse(dao.setStarted(_, now))
                } yield {
                  val newRunning = updatedRunning ++ newlyStarted
                  (
                    newRunning,
                    if (
                      newRunning
                        .map(_.spec)
                        .toSet == currentlyRunning.map(_.spec).toSet
                    )
                      None
                    else Some(newRunning)
                  )
                }
              }
              .mapFilter(_._2)

            runningJobs.switchMap { jobs =>
              Stream.eval(logger(RunningJobsUpdated(jobs))) *>
                Stream
                  .eval(jobs.traverse(j => jobPipe(j)))
                  .flatMap { pipes =>
                    val logPipeO: Option[Pipe[F, Message, Unit]] =
                      cfg.logEveryNMessage.map { n =>
                        (_: Stream[F, Message]).chunkN(n).evalMap { c =>
                          c.head
                            .traverse(m =>
                              logger(JobEvent.MessagesReceived(m, c.size))
                            )
                            .void
                        }
                      }

                    messageSubscriber.subscribe
                      .broadcastTo((pipes ++ logPipeO.toList): _*)
                  }
            }
          }

    }
}

/** @param jobCheckFrequency
  *   how often it checks new available jobs or running jobs being stopped.
  * @param jobProcessFrequency
  *   how often it does the task of a job.
  * @param jobObsoleteCount
  *   the threshold over which times a job misses being checkedOut will be count as
  *   obsolete and available for worker to pick up.
  * @param maxChunkSize
  *   when messages accumulate over maxChunkSize, they will be processed for the job.
  */
case class JobRunnerConfig(
    jobCheckFrequency: FiniteDuration,
    jobObsoleteCount: Long,
    maxChunkSize: Int,
    jobProcessFrequency: FiniteDuration,
    logEveryNMessage: Option[Int] = None)

object JobRunnerConfig {
  def fromConfig[F[_]: Sync](cfg: Config): F[JobRunnerConfig] = {
    import pureconfig.module.catseffect._
    ConfigSource.fromConfig(cfg).at("thomas.stream.job").loadF[F, JobRunnerConfig]
  }
}
