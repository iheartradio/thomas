package com.iheart.thomas
package stream

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.iheart.thomas.analysis.{
  BetaModel,
  ConversionKPI,
  ConversionKPIDAO,
  ConversionMessageQuery,
  KPIName,
  MessageQuery
}
import com.iheart.thomas.stream.JobSpec.UpdateKPIPrior
import org.scalatest.matchers.should.Matchers
import org.typelevel.jawn.ast.{JObject, JString, JValue}
import testkit.MapBasedDAOs
import fs2.Stream

import concurrent.duration._

class JobAlgSuite extends AsyncIOSpec with Matchers {
  def withAlg[A](
      f: (ConversionKPIDAO[IO], JobAlg[IO, JValue]) => IO[A]
    ) = {
    implicit val kpiDAO = MapBasedDAOs.conversionKPIDAO[IO]
    implicit val jobDAO = MapBasedDAOs.streamJobDAO[IO]

    val alg = JobAlg[IO, JValue](50.millis)
    f(kpiDAO, alg)
  }

  val kpiA = ConversionKPI(
    KPIName("A"),
    "kai",
    None,
    BetaModel(0, 0),
    Some(
      ConversionMessageQuery(
        MessageQuery(None, List("action" -> "display")),
        MessageQuery(None, List("action" -> "click"))
      )
    )
  )

  val createPubSub = PubSub.create[IO](event("type" -> "init"))

  def event(vs: (String, String)*) =
    JObject.fromSeq(vs.toList.map {
      case (k, v) =>
        k -> (JString(v): JValue)
    })

  "JobAlg" - {
    "can schedule a job" in withAlg { (_, alg) =>
      (for {
        job <- alg.schedule(UpdateKPIPrior(kpiA.name, sampleSize = 2))
        jobs <- alg.allJobs

      } yield (job, jobs)).asserting {
        case (job, jobs) => jobs.contains(job.get) shouldBe true
      }

    }

    "get can process one KPI update job" in withAlg { (kpiDAO, alg) =>
      (for {
        pubSub <- Stream.eval {
          kpiDAO.upsert(kpiA) *>
            alg.schedule(UpdateKPIPrior(kpiA.name, sampleSize = 2)) *>
            createPubSub
        }
        _ <- Stream(
          pubSub.subscribe.through(alg.runningPipe),
          pubSub
            .publish(event("action" -> "click"), event("action" -> "display"))
            .delayBy(300.millis)
        ).parJoin(2)

      } yield ()).interruptAfter(1.second).compile.drain *>
        kpiDAO.get(kpiA.name).asserting(_.model shouldBe BetaModel(2, 2))

    }

    "can stop job" in withAlg { (kpiDAO, alg) =>
      (for {
        pubSub <- Stream.eval {
          kpiDAO.upsert(kpiA) *> createPubSub
        }
        job <- Stream.eval(alg.schedule(UpdateKPIPrior(kpiA.name, sampleSize = 4)))
        _ <- Stream(
          pubSub.subscribe.through(alg.runningPipe),
          pubSub
            .publish(
              event("action" -> "click"),
              event("action" -> "display")
            )
            .delayBy(300.milliseconds),
          Stream.eval(alg.stop(job.get)).delayBy(1.second),
          pubSub
            .publish(event("action" -> "click"), event("action" -> "display"))
            .delayBy(1500.milliseconds)
        ).parJoin(4)

      } yield ()).interruptAfter(2.second).compile.drain *>
        kpiDAO
          .get(kpiA.name)
          .asserting(_.model shouldBe kpiA.model) //didn't reach sample size.

    }

    "remove job when completed" in withAlg { (kpiDAO, alg) =>
      (for {
        pubSub <- Stream.eval {
          kpiDAO.upsert(kpiA) *>
            alg.schedule(UpdateKPIPrior(kpiA.name, sampleSize = 2)) *>
            createPubSub
        }
        jobs <-
          Stream(
            pubSub.subscribe.through(alg.runningPipe),
            pubSub
              .publish(
                event("action" -> "click"),
                event("action" -> "display")
              )
              .delayBy(300.milliseconds)
          ).parJoin(4).interruptAfter(500.milliseconds).drain ++
            Stream.eval(alg.allJobs)

      } yield jobs).compile.toList.asserting {
        _ shouldBe List(Vector.empty[Job])
      }
    }
//
//    "can pick up abandoned obsolete job" in {
//    }
  }

}
