package com.iheart.thomas.cli

import java.time.OffsetDateTime

import cats.effect.ConcurrentEffect
import com.monovore.decline.{Command, Opts}
import cats.implicits._
import com.iheart.thomas.bandit.BanditSpec
import com.monovore.decline.time._
import BayesianBanditHttpClientOpts.conversionClientOpts
import com.iheart.thomas.analysis.KPIName
import io.estatico.newtype.ops._
import scala.concurrent.ExecutionContext

object BayesianMABCommands {
  val fnOpts = Opts.option[String]("feature", "feature name", "f")

  private val banditSpecOpts =
    (
      fnOpts,
      Opts.options[String]("arms", "list of arms", "a").map(_.toList),
      Opts.option[String]("author", "author name", "u"),
      Opts.option[OffsetDateTime]("start", "start time of the MAB"),
      Opts.option[String]("title", "author name", "u"),
      Opts.option[String]("kpi", "KPI name", "k").coerce[Opts[KPIName]],
      Opts
        .option[Double]("minimumSizeChange", "minimum group size change")
        .withDefault(0.005d),
      Opts
        .option[Int]("initialSampleSize", "required sample size to start allocating")
        .withDefault(0)
    ).mapN(BanditSpec.apply)

  def conversionBMABCommand[F[_]](
      implicit F: ConcurrentEffect[F],
      ec: ExecutionContext
    ) =
    Command(
      "conversionBMAB",
      "manage conversion based Bayesian Multi Arm Bandits"
    )(
      Opts.subcommands(
        Command("init", "init a new conversion KPI Bayesian MAB") {
          (banditSpecOpts, conversionClientOpts[F]).mapN { (spec, clientR) =>
            clientR.use { client =>
              client.init(spec)
            }
          }
        },
        Command(
          "show",
          "show an existing conversion KPI based Bayesian MAB"
        ) {
          (fnOpts, conversionClientOpts[F]).mapN { (feature, clientR) =>
            clientR.use { client =>
              client
                .currentState(feature)
                .flatMap(
                  s =>
                    F.delay {
                      println(
                        "=========== Bayesian State Start ============"
                      )
                      println(s)
                      println(
                        "=========== Bayesian State End ============="
                      )
                    }
                )
            }
          }
        },
        Command(
          "reallocate",
          "show an existing conversion KPI based Bayesian MAB"
        ) {
          (fnOpts, conversionClientOpts[F]).mapN { (feature, clientR) =>
            clientR.use { client =>
              client.reallocate(feature)
            }
          }
        }
      )
    )
}
