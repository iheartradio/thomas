package com.iheart.thomas
package stream

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.implicits._
import com.iheart.thomas.analysis.{
  BetaModel,
  ConversionKPI,
  ConversionKPIAlg,
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

import java.time.Instant
import concurrent.duration._
import com.iheart.thomas.testkit.ExampleArmParse._

abstract class JobAlgSuiteBase extends AsyncIOSpec with Matchers {
  def withAlg[A](
      f: (ConversionKPIAlg[IO], JobAlg[IO], PubSub[IO]) => IO[A]
    )(implicit config: Config = cfg
    ): IO[A] = {
    implicit val kpiDAO = MapBasedDAOs.conversionKPIAlg[IO]
    implicit val jobDAO = MapBasedDAOs.streamJobDAO[IO]
    implicit val eStateDAO = MapBasedDAOs.experimentStateDAO[IO, Conversions]

    PubSub.create[IO](event("type" -> "init")).flatMap { implicit pubSub =>
      f(kpiDAO, implicitly[JobAlg[IO]], pubSub)
    }

  }

  def cfg(d: FiniteDuration): Config = ConfigFactory.parseString(s"""
                                                                    |thomas {
                                                                    |  stream {
                                                                    |    job {
                                                                    |      job-check-frequency: $d
                                                                    |      min-chunk-size: 2
                                                                    |    }
                                                                    |  }  
                                                                    |}
                                                                    |""".stripMargin)

  val cfg: Config = cfg(50.millis)

  val kpiA = ConversionKPI(
    KPIName("A"),
    "kai",
    None,
    BetaModel(1, 1),
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
        job <- alg.schedule(UpdateKPIPrior(kpiA.name, Instant.now.plusSeconds(3)))
        jobs <- alg.allJobs

      } yield (job, jobs)).asserting {
        case (job, jobs) => jobs.contains(job.get) shouldBe true
      }

    }

    "set the started time when started" in withAlg { (kpiDAO, alg, pubSub) =>
      val spec = UpdateKPIPrior(kpiA.name, Instant.now.plusMillis(800))
      (for {
        _ <- Stream.eval {
          kpiDAO.create(kpiA) *>
            alg.schedule(spec)
        }
        _ <- alg.runStream

      } yield ()).interruptAfter(200.millis).compile.drain *>
        alg.find(spec)
          .asserting(_.flatMap(_.started).nonEmpty shouldBe true)

    }

    "get can process one KPI update job" in withAlg { (kpiDAO, alg, pubSub) =>
      (for {
        _ <- Stream.eval {
          kpiDAO.create(kpiA) *>
            alg.schedule(UpdateKPIPrior(kpiA.name, Instant.now.plusMillis(800)))
        }
        _ <- Stream(
          alg.runStream,
          pubSub
            .publish(event("action" -> "click"), event("action" -> "display"))
            .delayBy(200.millis)
        ).parJoin(2)

      } yield ()).interruptAfter(1.second).compile.drain *>
        kpiDAO
          .get(kpiA.name)
          .asserting(_.model shouldBe kpiA.model.updateFrom(Conversions(1, 1)))

    }

    "keep checkedout timestamp updated" in withAlg { (kpiDAO, alg, _) =>
      (for {
        _ <- Stream.eval {
          kpiDAO.create(kpiA) *>
            alg.schedule(UpdateKPIPrior(kpiA.name, Instant.now.plusSeconds(2)))
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
        _ <- Stream.eval(kpiDAO.create(kpiA))
        job <- Stream.eval(alg.schedule(UpdateKPIPrior(kpiA.name, Instant.now.plusSeconds(1))))
        _ <- Stream(
          alg.runStream,
          pubSub
            .publish(
              event("action" -> "click"),
              event("action" -> "display")
            )
            .delayBy(200.milliseconds),
          Stream.eval(alg.stop(job.get.key)).delayBy(500.milliseconds),
          pubSub
            .publish(event("action" -> "click"), event("action" -> "display"))
            .delayBy(800.milliseconds)
        ).parJoin(4)

      } yield ()).interruptAfter(2.second).compile.drain *>
        kpiDAO
          .get(kpiA.name)
          .asserting(_.model shouldBe kpiA.model.updateFrom(Conversions(1, 1)))
    }

    "remove job when completed" in withAlg { (kpiDAO, alg, pubSub) =>
      (for {
        _ <- Stream.eval {
          kpiDAO.create(kpiA) *>
            alg.schedule(UpdateKPIPrior(kpiA.name, Instant.now.plusMillis(400)))
        }
        jobs <-
          Stream(
            alg.runStream,
            pubSub
              .publish(
                event("action" -> "click"),
                event("action" -> "display")
              )
              .delayBy(200.milliseconds)
          ).parJoin(4).interruptAfter(600.milliseconds).drain ++
            Stream.eval(alg.allJobs)

      } yield jobs).compile.toList.asserting {
        _ shouldBe List(Vector.empty[Job])
      }
    }

    "can pickup new job" in withAlg { (kpiDAO, alg, pubSub) =>
      val kpiC = kpiA.copy(name = KPIName("C"))
      val kpiB = kpiA.copy(name = KPIName("B"))
      (for {
        _ <- Stream.eval(kpiDAO.create(kpiC) *> kpiDAO.create(kpiB))
        _ <- Stream.eval(alg.schedule(UpdateKPIPrior(kpiC.name, Instant.now.plusMillis(2400))))
        _ <- Stream(
          alg.runStream,
          pubSub
            .publish(
              event("action" -> "click"),
              event("action" -> "display"),
            )
            .delayBy(200.milliseconds),
          Stream
            .eval(alg.schedule(UpdateKPIPrior(kpiB.name, Instant.now.plusMillis(1800))))
            .delayBy(500.milliseconds),
          pubSub
            .publish(
              event("action" -> "display"),
              event("action" -> "display"),
            )
            .delayBy(800.milliseconds)
        ).parJoin(4)

      } yield ()).interruptAfter(3.second).compile.drain *>
        (kpiDAO.get(kpiC.name), kpiDAO.get(kpiB.name)).tupled.asserting {
          case (kC, kB) =>
            kB.model should be(kpiB.model.updateFrom(Conversions(0, 2)))
            kC.model should be(kpiC.model.updateFrom(Conversions(1, 3)))
        }
    }
  }
}
