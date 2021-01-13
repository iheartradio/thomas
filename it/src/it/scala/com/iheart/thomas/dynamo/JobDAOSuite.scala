package com.iheart.thomas
package dynamo

import cats.effect.IO
import testkit.Resources.localDynamoR
import cats.effect.testing.scalatest.AsyncIOSpec
import com.iheart.thomas.analysis.KPIName
import com.iheart.thomas.stream.JobSpec.{UpdateBandit, UpdateKPIPrior}
import com.iheart.thomas.stream.{Job, JobDAO}
import org.scalatest.matchers.should.Matchers
import cats.implicits._
import lihua.dynamo.ScanamoEntityDAO.ScanamoError

import java.time.Instant

class JobDAOSuite extends AsyncIOSpec with Matchers {

  type F[A] = IO[A]

  def withDAO[A](f: JobDAO[F] => F[A]): F[A] =
    localDynamoR.use { implicit ld =>
      f(AdminDAOs.streamJobDAO)
    }

  val jobSpec = UpdateKPIPrior(KPIName("foo"))

  "Scanamo JobDAO" - {
    "insertO new job" in {
      val job = Job(jobSpec)
      withDAO { dao =>
        dao.insertO(job) >>
          dao.all
      }.asserting(_ shouldBe Vector(job))
    }

    "remove a job" in {
      val job = Job(jobSpec)
      withDAO { dao =>
        dao.insertO(job) >>
          dao.remove(job.key) >>
          dao.all
      }.asserting(_ should be(empty))
    }

    "cannot re-insert same job with the same key" in {
      val job = Job(jobSpec.copy(sampleSize = 100))
      val job2 = Job(jobSpec.copy(sampleSize = 200))
      withDAO { dao =>
        dao.insertO(job) >>
          dao.insertO(job2)
      }.asserting(_ should be(empty))
    }

    "update checkedOut successfully when the job is unchecked yet" in {
      val newTimeStamp = Instant.now
      withDAO { dao =>
        for {
          job <- dao.insertO(Job(jobSpec))
          jobUpdated <- dao.updateCheckedOut(job.get, newTimeStamp)
        } yield jobUpdated

      }.asserting(
        _.flatMap(_.checkedOut.map(_.toEpochMilli)) shouldBe Some(
          newTimeStamp.toEpochMilli //data stored in dynamo lost some precision
        )
      )
    }

    "update checkedOut successfully when the job has matched checkedOut timestamp" in {
      val newTimeStamp = Instant.now.plusSeconds(10)

      withDAO { dao =>
        for {
          job <- dao.insertO(Job(jobSpec))
          jobUpdated <- dao.updateCheckedOut(job.get, Instant.now)
          jobUpdatedAgain <- dao.updateCheckedOut(jobUpdated.get, newTimeStamp)
        } yield jobUpdatedAgain

      }.asserting(
        _.flatMap(_.checkedOut.map(_.toEpochMilli)) shouldBe Some(
          newTimeStamp.toEpochMilli
        )
      )
    }

    "update checkedOut unsuccessfully when data does not match" in {
      withDAO { dao =>
        for {
          started <- dao.insertO(Job(jobSpec))
          updated <- dao.updateCheckedOut(started.get, Instant.now)
          _ <- dao.updateCheckedOut(updated.get, Instant.now.plusSeconds(10))
          outdated = updated.get
          tryUpdate <- dao.updateCheckedOut(
            outdated,
            Instant.now.plusSeconds(20)
          )
        } yield tryUpdate

      }.asserting(_ should be(empty))
    }

  }

}
