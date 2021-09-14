package com.iheart.thomas.stream

import java.time.Instant

/** Job to process Stream of messages
  *
  * @param spec
  *   @param checkedOut last time a worker reported that it's working on it
  * @param started
  *   latest start time when a worker started working on it.
  */
case class Job(
    key: String,
    spec: JobSpec,
    checkedOut: Option[Instant],
    started: Option[Instant])

case class JobInfo[JS <: JobSpec](
    started: Option[Instant],
    spec: JS)

object Job {
  def apply(spec: JobSpec): Job = Job(spec.key, spec, None, None)
}

/** A DAO for job. Implemenation should pass thomas.stream.JobDAOSuite in the tests
  * module.
  * @tparam F
  */
trait JobDAO[F[_]] {

  /** @return
    *   None if job with the same key already exist.
    */
  def insertO(job: Job): F[Option[Job]]

  /** Update checkedOut but fails when the existing data is inconsistent with given
    * `job`
    * @return
    *   None if either the job no longer exist or its signature is different, i.e.
    *   checkedOut is inconsistent
    */
  def updateCheckedOut(
      job: Job,
      at: Instant
    ): F[Option[Job]]

  def setStarted(
      job: Job,
      at: Instant
    ): F[Job]

  def remove(jobKey: String): F[Unit]

  def find(jobKey: String): F[Option[Job]]

  def all: F[Vector[Job]]

}
