package com.iheart.thomas.stream

import java.time.Instant

/**
  * Job to process Stream of messages
  *
  * @param spec
  * @param checkedOut
  */
case class Job(
    key: String,
    spec: JobSpec,
    checkedOut: Option[Instant])

object Job {
  def apply(spec: JobSpec): Job = Job(spec.key, spec, None)
}

trait JobDAO[F[_]] {

  /**
    * @return None if job with the same key already exist.
    */
  def insertO(job: Job): F[Option[Job]]

  /**
    * Update checkedOut but fails when the existing data is inconsistent with the `consumer` given
    */
  def updateCheckedOut(
      job: Job,
      at: Instant
    ): F[Option[Job]]

  def remove(jobKey: String): F[Unit]

  def all: F[Vector[Job]]

}
