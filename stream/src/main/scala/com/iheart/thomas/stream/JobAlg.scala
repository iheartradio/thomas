package com.iheart.thomas.stream

import cats.effect.{Concurrent, Sync, Timer}
import cats.implicits._
import com.iheart.thomas.{FeatureName, TimeUtil}
import TimeUtil._
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.Key
import com.iheart.thomas.analysis.monitor.MonitorAlg
import com.iheart.thomas.analysis.{ConversionKPIAlg, ConversionMessageQuery, KPIName}
import com.iheart.thomas.stream.JobSpec.{MonitorTest, RunBandit, UpdateKPIPrior}
import com.typesafe.config.Config
import fs2._
import pureconfig.ConfigSource

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

trait JobAlg[F[_]] {

  /**
    * Creates a job if the job key is not already in the job list
    * @return Some(job) if successful, None otherwise.
    */
  def schedule(spec: JobSpec): F[Option[Job]]

  /**
    * Stops and removes a job
    */
  def stop(jobKey: String): F[Unit]

  def runStream: Stream[F, Unit]

  def allJobs: F[Vector[Job]]

  def findInfo[JS <: JobSpec: ClassTag](
      condition: JS => Boolean
    ): F[Vector[JobInfo[JS]]]

  def monitors(feature: FeatureName): F[Vector[JobInfo[MonitorTest]]] =
    findInfo((m: MonitorTest) => m.feature == feature)

  /**
    * Find job according to spec. Since you can't run two jobs with the same key, find ignores the rest of the spec.
    * @param spec
    * @return
    */
  def find(spec: JobSpec): F[Option[Job]]

}

object JobAlg {

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
      cKpiAlg: ConversionKPIAlg[F],
      armParser: ArmParser[F, Message],
      monitorAlg: MonitorAlg[F],
      convParser: ConversionParser[F, Message],
      config: Config,
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

              def checkJobComplete(until: Instant) = {
                Stream
                  .fixedDelay(cfg.jobCheckFrequency)
                  .evalMap(_ => until.passed)
                  .evalTap { completed =>
                    if (completed) stop(job.key)
                    else F.unit
                  }
              }

              def kpiQuery(name: KPIName): F[ConversionMessageQuery] =
                cKpiAlg
                  .get(name)
                  .flatMap { kpi =>
                    kpi.messageQuery
                      .liftTo[F](CannotUpdateKPIWithoutQuery(name))
                  }

              job.spec match {
                case UpdateKPIPrior(kpiName, until) =>
                  kpiQuery(kpiName)
                    .map {
                      query =>
                        { (input: Stream[F, Message]) =>
                          input
                            .evalMapFilter(m => convParser.parseConversion(m, query))
                            .interruptWhen(checkJobComplete(until))
                            .chunkMin(cfg.minChunkSize)
                            .evalMap { chunk =>
                              cKpiAlg.updateModel(
                                kpiName,
                                chunk
                              )
                            }
                            .void
                        }
                    }

                case MonitorTest(feature, kpiName, expiration) =>
                  kpiQuery(kpiName)
                    .map {
                      query =>
                        { (input: Stream[F, Message]) =>
                          Stream.eval(monitorAlg.initConversion(feature, kpiName)) *>
                            input
                              .evalMapFilter { m =>
                                armParser.parseArm(m, feature).flatMap { armO =>
                                  armO.flatTraverse { arm =>
                                    convParser
                                      .parseConversion(m, query)
                                      .map(_.map((arm, _)))
                                  }
                                }
                              }
                              .interruptWhen(checkJobComplete(expiration))
                              .chunkMin(cfg.minChunkSize)
                              .evalMap { chunk =>
                                monitorAlg.updateState(Key(feature, kpiName), chunk)
                              }
                              .void
                        }

                    }

                case RunBandit(_) => ???
              }
            }

            val availableJobs: Stream[F, Vector[Job]] =
              Stream
                .fixedDelay[F](cfg.jobCheckFrequency)
                .evalMap(_ =>
                  dao.all.map(_.filter(_.checkedOut.isEmpty))
                ) //todo: be resilient against DB error with logging.

            val runningJobs = availableJobs
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
                  now <- TimeUtil.now[F]
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
              Stream
                .eval(jobs.traverse(j => jobPipe(j)))
                .flatMap { pipes =>
                  messageSubscriber.subscribe
                    .broadcastTo(pipes: _*)
                }
            }
          }

    }
}

/**
  *
  * @param jobCheckFrequency how often it checks new available jobs or running jobs being stoped.
  */
case class JobRunnerConfig(
    jobCheckFrequency: FiniteDuration,
    minChunkSize: Int)

object JobRunnerConfig {
  def fromConfig[F[_]: Sync](cfg: Config): F[JobRunnerConfig] = {
    import pureconfig.generic.auto._
    import pureconfig.module.catseffect._
    ConfigSource.fromConfig(cfg).at("thomas.stream.job").loadF[F, JobRunnerConfig]
  }
}
