package com.iheart.thomas.stream

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Resource}
import com.iheart.thomas.analysis.KPIName
import com.iheart.thomas.dynamo.AdminDAOs
import com.iheart.thomas.stream.JobSpec.{ProcessSettingsOptional, UpdateKPIPrior}
import com.iheart.thomas.testkit.MapBasedDAOs
import com.iheart.thomas.testkit.Resources.localDynamoR
import com.iheart.thomas.utils.time.epochDay
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

abstract class JobDAOSuite(daoR: Resource[IO, JobDAO[IO]])
    extends AsyncFreeSpec
    with AsyncIOSpec
    with Matchers {

  val jobSpec = UpdateKPIPrior(
    KPIName("foo"),
    ProcessSettingsOptional(None, None, Some(Instant.parse("2021-01-30T00:00:00Z")))
  )

  "JobDAO" - {
    "insertO new job" in {
      val job = Job(jobSpec)
      daoR
        .use { dao =>
          dao.insertO(job) >>
            dao.all
        }
        .asserting(_ shouldBe Vector(job))
    }

    "remove a job" in {
      val job = Job(jobSpec)
      daoR
        .use { dao =>
          dao.insertO(job) >>
            dao.remove(job.key) >>
            dao.all
        }
        .asserting(_ should be(empty))
    }

    "cannot re-insert same job with the same key" in {
      val job = Job(
        jobSpec.copy(processSettings = ProcessSettingsOptional(None, None, None))
      )
      val job2 = Job(jobSpec)
      daoR
        .use { dao =>
          dao.insertO(job) >>
            dao.insertO(job2)
        }
        .asserting(_ should be(empty))
    }

    "update started" in {
      val job = Job(jobSpec)
      daoR
        .use { dao =>
          dao.insertO(job).flatMap { j =>
            dao.setStarted(j.get, epochDay)
          }
        }
        .asserting(_.started shouldBe Some(epochDay))
    }

    "update checkedOut successfully when the job is unchecked yet" in {
      daoR
        .use { dao =>
          for {
            job <- dao.insertO(Job(jobSpec))
            jobUpdated <- dao.updateCheckedOut(job.get, epochDay)
          } yield jobUpdated
        }
        .asserting(
          _.flatMap(_.checkedOut) shouldBe Some(
            epochDay
          )
        )
    }

    "update checkedOut successfully when the job has matched checkedOut timestamp" in {
      val newTimeStamp = Instant.now.plusSeconds(10)
      daoR
        .use { dao =>
          for {
            job <- dao.insertO(Job(jobSpec))
            jobUpdated <- dao.updateCheckedOut(job.get, Instant.now)
            jobUpdatedAgain <- dao.updateCheckedOut(jobUpdated.get, newTimeStamp)
          } yield jobUpdatedAgain

        }
        .asserting(
          _.flatMap(_.checkedOut.map(_.toEpochMilli)) shouldBe Some(
            newTimeStamp.toEpochMilli
          )
        )
    }

    "update checkedOut unsuccessfully when data does not match" in {
      daoR
        .use { dao =>
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

        }
        .asserting(_ should be(empty))
    }

  }

}

class DynamoJobDAOSuite
    extends JobDAOSuite(localDynamoR.map(implicit ld => AdminDAOs.streamJobDAO))

class InMemoryDAOSuite
    extends JobDAOSuite(Resource.eval(IO.delay(MapBasedDAOs.streamJobDAO[IO])))
