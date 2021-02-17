package com.iheart.thomas.stream

import cats.effect.{Concurrent, Sync, Timer}
import cats.implicits._
import com.iheart.thomas.{FeatureName, TimeUtil}
import TimeUtil.InstantOps
import cats.Functor
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.Key
import com.iheart.thomas.analysis.monitor.MonitorAlg
import com.iheart.thomas.analysis.{ConversionKPIAlg, ConversionMessageQuery, KPIName}
import com.iheart.thomas.stream.JobSpec.{MonitorTest, RunBandit, UpdateKPIPrior}
import com.iheart.thomas.tracking.EventLogger
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

  def findInfo[JS <: JobSpec: ClassTag](
      key: String
    )(implicit F: Functor[F]
    ): F[Option[JobInfo[JS]]] =
    findInfo[JS]((_: JS).key == key).map(_.headOption)

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

              def kpiQuery(name: KPIName): F[ConversionMessageQuery] =
                cKpiAlg
                  .get(name)
                  .flatMap { kpi =>
                    kpi.messageQuery
                      .liftTo[F](CannotUpdateKPIWithoutQuery(name))
                  }

              def chunkPipe[A](until: Instant): Pipe[F, A, Chunk[A]] =
                (input: Stream[F, A]) => {
                  val checkJobComplete = Stream
                    .fixedDelay(cfg.jobCheckFrequency)
                    .evalMap(_ => until.passed)
                    .evalTap { completed =>
                      if (completed) stop(job.key)
                      else F.unit
                    }
                  input
                    .interruptWhen(checkJobComplete)
                    .groupWithin(cfg.maxChunkSize, cfg.jobProcessFrequency)

                }

              job.spec match {
                case UpdateKPIPrior(kpiName, until) =>
                  kpiQuery(kpiName)
                    .map {
                      query =>
                        { (input: Stream[F, Message]) =>
                          input
                            .evalMap(m => convParser.parseConversion(m, query))
                            .filter(_.nonEmpty)
                            .through(chunkPipe(until))
                            .evalMap { chunk =>
                              cKpiAlg.updateModel(
                                kpiName,
                                chunk.toList.flatten
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
                              .evalMap { m =>
                                armParser.parseArm(m, feature).flatMap { armO =>
                                  armO.toList.flatTraverse { arm =>
                                    convParser
                                      .parseConversion(m, query)
                                      .map(_.map((arm, _)))
                                  }
                                }
                              }
                              .filter(_.nonEmpty)
                              .through(chunkPipe(expiration))
                              .evalMap { chunk =>
                                monitorAlg.updateState(
                                  Key(feature, kpiName),
                                  chunk.toList.flatten
                                )
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
                  TimeUtil.now[F].flatMap { now =>
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
                  (if (cfg.logEveryMessage)
                     messageSubscriber.subscribe
                       .flatTap(m =>
                         Stream.eval(logger(JobEvent.MessageReceived(m)))
                       )
                   else messageSubscriber.subscribe)
                    .broadcastTo(pipes: _*)
                }
            }
          }

    }
}

/**
  *
  * @param jobCheckFrequency how often it checks new available jobs or running jobs being stoped.
  * @param jobProcessFrequency how often it does the task of a job.
  * @param jobObsoleteCount the threshold over which times a job misses being checkedOut will be count as obsolete and available for worker to pick up.
  * @param maxChunkSize when messages accumulate over maxChunkSize, they will be processed for the job.
  */
case class JobRunnerConfig(
    jobCheckFrequency: FiniteDuration,
    jobObsoleteCount: Long,
    maxChunkSize: Int,
    jobProcessFrequency: FiniteDuration,
    logEveryMessage: Boolean = false)

object JobRunnerConfig {
  def fromConfig[F[_]: Sync](cfg: Config): F[JobRunnerConfig] = {
    import pureconfig.generic.auto._
    import pureconfig.module.catseffect._
    ConfigSource.fromConfig(cfg).at("thomas.stream.job").loadF[F, JobRunnerConfig]
  }
}
