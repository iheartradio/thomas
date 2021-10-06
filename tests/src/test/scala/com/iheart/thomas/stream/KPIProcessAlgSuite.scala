package com.iheart.thomas.stream

import breeze.stats.meanAndVariance
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.iheart.thomas.analysis._
import com.iheart.thomas.analysis.bayesian.KPIIndicator
import com.iheart.thomas.analysis.bayesian.models.{LogNormalModel, NormalModel}
import com.iheart.thomas.stream.JobSpec.ProcessSettings
import com.iheart.thomas.testkit.MockQueryAccumulativeKPIAlg.MockData
import com.iheart.thomas.testkit.{Factory, MapBasedDAOs, MockQueryAccumulativeKPIAlg}
import com.iheart.thomas.tracking.EventLogger
import com.stripe.rainier.sampler.{RNG, SamplerConfig}
import fs2.Stream
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration._
class KPIProcessAlgSuite extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  val testKPIName = KPIName("test")

  def testQueryAccumulativeKPI[A](
      kpi: QueryAccumulativeKPI,
      data: List[MockData[PerUserSamples]]
    )(f: (
          KPIProcessAlg[IO, Unit, QueryAccumulativeKPI],
          KPIRepo[IO, QueryAccumulativeKPI]
      ) => IO[A]
    ): IO[A] = {

    implicit val eventLogger = EventLogger.noop[IO]
    implicit val aKpiDAO = MapBasedDAOs.queryAccumulativeKPIAlg[IO]
    implicit val aStateDAO =
      MapBasedDAOs.experimentStateDAO[IO, PerUserSamplesLnSummary]
    implicit val mockAlg =
      MockQueryAccumulativeKPIAlg[IO](
        data,
        50.millis
      )
    aKpiDAO.create(kpi) *>
      f(
        KPIProcessAlg.default,
        aKpiDAO
      )

  }
  def settings(frequency: FiniteDuration = 10.millis): ProcessSettings =
    ProcessSettings(frequency, 1, None)
  val blindPrior = LogNormalModel(NormalModel(1d, 1d, 1d, 1d))
  implicit val rng = RNG.default
  implicit val sampler = SamplerConfig.default
  def process(
      kpi: QueryAccumulativeKPI,
      alg: KPIProcessAlg[IO, Unit, QueryAccumulativeKPI],
      ps: ProcessSettings = settings(),
      duration: FiniteDuration = 150.millis
    ): IO[Unit] =
    Stream
      .fromIterator[IO](Iterator(()), 1)
      .through(alg.updatePrior(kpi, ps))
      .interruptAfter(duration)
      .compile
      .drain

  "empty data results in unchanged prior" in {
    val kpi =
      Factory.kpi(testKPIName, blindPrior, MockQueryAccumulativeKPIAlg.mockQueryName)
    testQueryAccumulativeKPI(
      kpi,
      Nil
    ) { (alg, repo) =>
      (for {

        _ <- process(kpi, alg)
        r <- repo.get(testKPIName)
      } yield r).asserting(_.model shouldBe blindPrior)
    }
  }

  "update prior according to data" in {
    val kpi =
      Factory.kpi(testKPIName, blindPrior, MockQueryAccumulativeKPIAlg.mockQueryName)
    val n = 10000
    val dist = breeze.stats.distributions.LogNormal(1d, 0.3d)
    val data = dist.sample(n).toArray

    testQueryAccumulativeKPI(
      kpi,
      List(
        (
          "fn",
          "A",
          testKPIName,
          Instant.now.minusSeconds(20),
          Instant.now.plusSeconds(20),
          PerUserSamples(data)
        )
      )
    ) { (alg, repo) =>
      (for {
        _ <- process(kpi, alg, duration = 200.millis)
        k <- repo.get(testKPIName)
      } yield k).asserting { k =>
        val meanStats = meanAndVariance(KPIIndicator.sample(k.model))
        meanStats.mean should be(dist.mean +- (dist.mean * 0.05d))
      }
    }
  }
}
