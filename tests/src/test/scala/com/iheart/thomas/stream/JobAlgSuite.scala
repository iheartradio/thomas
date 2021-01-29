package com.iheart.thomas
package stream

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.implicits._
import com.iheart.thomas.analysis.{
  BetaModel,
  ConversionKPI,
  ConversionKPIDAO,
  ConversionMessageQuery,
  Conversions,
  KPIName,
  MessageQuery
}
import com.iheart.thomas.stream.JobSpec.UpdateKPIPrior
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.matchers.should.Matchers
import org.typelevel.jawn.ast.{JObject, JString, JValue}
import testkit.MapBasedDAOs
import fs2.Stream

import concurrent.duration._

abstract class JobAlgSuiteBase extends AsyncIOSpec with Matchers {
  def withAlg[A](
      f: (ConversionKPIDAO[IO], JobAlg[IO], PubSub[IO]) => IO[A]
    )(implicit config: Config = cfg
    ): IO[A] = {
    implicit val kpiDAO = MapBasedDAOs.conversionKPIDAO[IO]
    implicit val jobDAO = MapBasedDAOs.streamJobDAO[IO]

    PubSub.create[IO](event("type" -> "init")).flatMap { implicit pubSub =>
      f(kpiDAO, implicitly[JobAlg[IO]], pubSub)
    }

  }

  def cfg(d: FiniteDuration): Config = ConfigFactory.parseString(s"""
                                                                    |thomas {
                                                                    |  stream {
                                                                    |    job {
                                                                    |      job-check-frequency: $d
                                                                    |    }
                                                                    |  }  
                                                                    |}
                                                                    |""".stripMargin)

  val cfg: Config = cfg(50.millis)

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

  def event(vs: (String, String)*) =
    JObject.fromSeq(vs.toList.map {
      case (k, v) =>
        k -> (JString(v): JValue)
    })

}

class JobAlgSuite extends JobAlgSuiteBase {

  "JobAlg" - {
    "can schedule a job" in withAlg { (_, alg, _) =>
      (for {
        job <- alg.schedule(UpdateKPIPrior(kpiA.name, sampleSize = 2))
        jobs <- alg.allJobs

      } yield (job, jobs)).asserting {
        case (job, jobs) => jobs.contains(job.get) shouldBe true
      }

    }

    "get can process one KPI update job" in withAlg { (kpiDAO, alg, pubSub) =>
      (for {
        _ <- Stream.eval {
          kpiDAO.insert(kpiA) *>
            alg.schedule(UpdateKPIPrior(kpiA.name, sampleSize = 2))
        }
        _ <- Stream(
          alg.runStream,
          pubSub
            .publish(event("action" -> "click"), event("action" -> "display"))
            .delayBy(300.millis)
        ).parJoin(2)

      } yield ()).interruptAfter(1.second).compile.drain *>
        kpiDAO
          .get(kpiA.name)
          .asserting(_.model shouldBe kpiA.model.updateFrom(Conversions(1, 2)))

    }

    "keep checkedout timestamp updated" in withAlg { (kpiDAO, alg, pubSub) =>
      (for {
        _ <- Stream.eval {
          kpiDAO.insert(kpiA) *>
            alg.schedule(UpdateKPIPrior(kpiA.name, sampleSize = 2))
        }
        start <- Stream.eval(TimeUtil.now[IO])
        _ <-
          alg.runStream
            .interruptAfter(1.second)
            .last
        jobs <- Stream.eval(alg.allJobs)

      } yield (start, jobs)).compile.toList.asserting { r =>
        val start = r.head._1
        val job = r.head._2.head
        job.checkedOut.get.isAfter(start.plusMillis(700)) shouldBe true
      }

    }

    "can stop job" in withAlg { (kpiDAO, alg, pubSub) =>
      (for {
        _ <- Stream.eval(kpiDAO.insert(kpiA))
        job <- Stream.eval(alg.schedule(UpdateKPIPrior(kpiA.name, sampleSize = 4)))
        _ <- Stream(
          alg.runStream,
          pubSub
            .publish(
              event("action" -> "click"),
              event("action" -> "display")
            )
            .delayBy(300.milliseconds),
          Stream.eval(alg.stop(job.get.key)).delayBy(1.second),
          pubSub
            .publish(event("action" -> "click"), event("action" -> "display"))
            .delayBy(1500.milliseconds)
        ).parJoin(4)

      } yield ()).interruptAfter(2.second).compile.drain *>
        kpiDAO
          .get(kpiA.name)
          .asserting(_.model shouldBe kpiA.model) //didn't reach sample size.
    }

    "remove job when completed" in withAlg { (kpiDAO, alg, pubSub) =>
      (for {
        _ <- Stream.eval {
          kpiDAO.insert(kpiA) *>
            alg.schedule(UpdateKPIPrior(kpiA.name, sampleSize = 2))
        }
        jobs <-
          Stream(
            alg.runStream,
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

    "can pickup new job" in withAlg { (kpiDAO, alg, pubSub) =>
      val kpiC = kpiA.copy(name = KPIName("C"))
      val kpiB = kpiA.copy(name = KPIName("B"))
      (for {
        _ <- Stream.eval(kpiDAO.insert(kpiC) *> kpiDAO.insert(kpiB))
        _ <- Stream.eval(alg.schedule(UpdateKPIPrior(kpiC.name, sampleSize = 4)))
        _ <- Stream(
          alg.runStream,
          pubSub
            .publish(
              event("action" -> "click", "b" -> "1"),
              event("action" -> "display", "b" -> "1")
            )
            .delayBy(300.milliseconds),
          Stream
            .eval(alg.schedule(UpdateKPIPrior(kpiB.name, sampleSize = 2)))
            .delayBy(500.milliseconds),
          pubSub
            .publish(
              event("action" -> "display", "b" -> "2"),
              event("action" -> "display", "b" -> "2")
            )
            .delayBy(800.milliseconds)
        ).parJoin(4)

      } yield ()).interruptAfter(2.second).compile.drain *>
        (kpiDAO.get(kpiC.name), kpiDAO.get(kpiB.name)).tupled.asserting {
          case (kC, kB) =>
            kB.model should be(kpiB.model.updateFrom(Conversions(0, 2)))
            kC.model should be(kpiC.model.updateFrom(Conversions(1, 4)))
        }
    }
  }
}
