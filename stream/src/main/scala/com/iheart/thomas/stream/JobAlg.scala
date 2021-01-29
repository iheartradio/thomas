package com.iheart.thomas.stream

import cats.effect.{Concurrent, Sync, Timer}
import cats.implicits._
import com.iheart.thomas.TimeUtil
import com.iheart.thomas.analysis.{ConversionKPIDAO, Conversions, KPIName}
import com.iheart.thomas.stream.JobSpec.UpdateKPIPrior
import com.typesafe.config.Config
import fs2._
import pureconfig.ConfigSource

import scala.concurrent.duration.FiniteDuration
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

  /**
    * Find job according to spec. Since you can't run two jobs with the same key, find ignores the rest of the spec.
    * @param spec
    * @return
    */
  def find(spec: JobSpec): F[Option[Job]]

}

object JobAlg {

  case class CannotUpdateKPIWithoutQuery(kpiName: KPIName)
      extends RuntimeException
      with NoStackTrace

  implicit def apply[F[_], Message](
      implicit F: Concurrent[F],
      timer: Timer[F],
      dao: JobDAO[F],
      cKpiDAO: ConversionKPIDAO[F],
      parser: ConversionParser[F, Message],
      config: Config,
      messageSubscriber: MessageSubscriber[F, Message]
    ): JobAlg[F] =
    new JobAlg[F] {
      def schedule(spec: JobSpec): F[Option[Job]] = dao.insertO(Job(spec))

      def stop(jobKey: String): F[Unit] = dao.remove(jobKey)

      def find(spec: JobSpec): F[Option[Job]] = dao.find(spec.key)

      def allJobs: F[Vector[Job]] = dao.all

      def jobPipe(job: Job): F[Pipe[F, Message, Unit]] =
        job.spec match {
          case UpdateKPIPrior(kpiName, sampleSize) =>
            cKpiDAO
              .get(kpiName)
              .flatMap { kpi =>
                kpi.messageQuery
                  .liftTo[F](CannotUpdateKPIWithoutQuery(kpiName))
                  .map {
                    query =>
                      { (input: Stream[F, Message]) =>
                        input
                          .evalMap(m => parser.parseConversion(m, query))
                          .flattenOption
                          .take(sampleSize.toLong)
                          .chunks
                          .foldMap { chunk =>
                            Conversions(chunk.count(identity), chunk.size.toLong)
                          }
                          .evalMap { c =>
                            cKpiDAO
                              .updateModel(kpiName, kpi.model.updateFrom(c))
                              .void *>
                              stop(job.key)
                          }
                      }
                  }
              }

          case _ => ???
        }

      def runStream: Stream[F, Unit] =
        Stream
          .eval(JobRunnerConfig.fromConfig(config))
          .flatMap { cfg =>
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
                } yield {
                  val newRunning = updatedRunning ++ newlyCheckedout
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
              Stream.eval(jobs.traverse(j => jobPipe(j))).flatMap { pipes =>
                messageSubscriber.subscribe.broadcastTo(pipes: _*)
              }
            }
          }
    }
}

/**
  *
  * @param jobCheckFrequency how often it checks new available jobs or running jobs being stoped.
  */
case class JobRunnerConfig(jobCheckFrequency: FiniteDuration)

object JobRunnerConfig {
  def fromConfig[F[_]: Sync](cfg: Config): F[JobRunnerConfig] = {
    import pureconfig.generic.auto._
    import pureconfig.module.catseffect._
    ConfigSource.fromConfig(cfg).at("thomas.stream.job").loadF[F, JobRunnerConfig]
  }
}
