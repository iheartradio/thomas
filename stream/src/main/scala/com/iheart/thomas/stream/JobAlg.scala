package com.iheart.thomas.stream

import cats.effect.{Concurrent, Timer}
import cats.implicits._
import com.iheart.thomas.TimeUtil
import com.iheart.thomas.analysis.{ConversionKPIDAO, Conversions, KPIName}
import com.iheart.thomas.stream.JobSpec.UpdateKPIPrior
import fs2._

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

trait JobAlg[F[_], Message] {

  /**
    * Creates a job if the job key is not already in the job list
    * @return Some(job) if successful, None otherwise.
    */
  def schedule(spec: JobSpec): F[Option[Job]]

  /**
    * Stops and removes a job
    */
  def stop(job: Job): F[Unit]

  /**
    * A pipe for consuming message.
    * Each pipe processes messages for running jobs. Pipe changes when job changes.
    * @return
    */
  def runningPipe: Pipe[F, Message, Unit]

  def allJobs: F[Vector[Job]]

}

object JobAlg {

  case class CannotUpdateKPIWithoutQuery(kpiName: KPIName)
      extends RuntimeException
      with NoStackTrace

  def apply[F[_], Message](
      jobCheckFrequency: FiniteDuration
    )(implicit F: Concurrent[F],
      timer: Timer[F],
      dao: JobDAO[F],
      cKpiDAO: ConversionKPIDAO[F],
      parser: ConversionParser[F, Message]
    ): JobAlg[F, Message] =
    new JobAlg[F, Message] {
      def schedule(spec: JobSpec): F[Option[Job]] = dao.insertO(Job(spec))

      def stop(job: Job): F[Unit] = dao.remove(job.key)

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
                              stop(job)
                          }
                      }
                  }
              }

          case _ => ???
        }

      def runningPipe: Pipe[F, Message, Unit] = {
        val availableJobs: Stream[F, Vector[Job]] =
          Stream
            .fixedDelay[F](jobCheckFrequency)
            .evalMap(_ => dao.all.map(_.filter(_.checkedOut.isEmpty)))

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
                  newRunning.map(_.spec).toSet == currentlyRunning.map(_.spec).toSet
                )
                  None
                else Some(newRunning)
              )
            }

          }
          .mapFilter(_._2)

        (input: Stream[F, Message]) =>
          runningJobs.switchMap { jobs =>
            Stream.eval(jobs.traverse(j => jobPipe(j))).flatMap { pipes =>
              input.broadcastTo(pipes: _*)
            }
          }
      }
    }
}
