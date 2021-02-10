package com.iheart.thomas.stream

import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.implicits._
import com.iheart.thomas.TimeUtil.epochDay
import com.iheart.thomas.analysis.KPIName
import com.iheart.thomas.dynamo.AdminDAOs
import com.iheart.thomas.stream.JobSpec.UpdateKPIPrior
import com.iheart.thomas.testkit.MapBasedDAOs
import com.iheart.thomas.testkit.Resources.localDynamoR
import org.scalatest.matchers.should.Matchers

import java.time.{Instant, LocalDate, LocalDateTime, OffsetDateTime, ZoneOffset}

abstract class JobDAOSuite(daoR: Resource[IO, JobDAO[IO]])
    extends AsyncIOSpec
    with Matchers {

  val jobSpec = UpdateKPIPrior(KPIName("foo"), Instant.parse("2021-01-30T00:00:00Z"))

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
      val job = Job(jobSpec.copy(until = Instant.now))
      val job2 = Job(jobSpec.copy(until = Instant.now.plusSeconds(1)))
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
    extends JobDAOSuite(Resource.liftF(IO.delay(MapBasedDAOs.streamJobDAO[IO])))
