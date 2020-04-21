package com.iheart.thomas
package cli

import java.time.OffsetDateTime

import cats.effect.ConcurrentEffect
import com.monovore.decline.{Argument, Command, Opts}
import cats.implicits._
import com.iheart.thomas.bandit.BanditSpec
import com.monovore.decline.time._
import BayesianBanditHttpClientOpts.conversionClientOpts
import cats.data.{Validated, ValidatedNel}
import com.iheart.thomas.analysis.KPIName
import com.iheart.thomas.bandit.bayesian.BanditSettings
import io.estatico.newtype.ops._
import com.monovore.decline.time._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

object BayesianMABCommands {
  val fnOpts = Opts.option[String]("feature", "feature name", "f")

  private val conversionSettingsOps = (
    Opts
      .option[Int]("eventChunkSize", "chunk size for kpi update")
      .withDefault(300),
    Opts
      .option[Int](
        "reallocateEveryNChunk",
        "number of chunks for every reallocating"
      )
      .withDefault(3)
  ).mapN(BanditSettings.Conversion.apply)

  implicit val durationArg: Argument[FiniteDuration] = new Argument[FiniteDuration] {

    override def read(string: String): ValidatedNel[String, FiniteDuration] =
      Validated
        .fromTry(Try(Duration(string).asInstanceOf[FiniteDuration]))
        .leftMap(_.getMessage)
        .toValidatedNel

    override def defaultMetavar: String = "duration string"
  }

  private val banditSettingsOps =
    (
      fnOpts,
      Opts.option[String]("author", "author name", "u"),
      Opts.option[String]("title", "author name", "u"),
      Opts.option[String]("kpi", "KPI name", "k").coerce[Opts[KPIName]],
      Opts
        .option[Double]("minimumSizeChange", "minimum group size change")
        .withDefault(0.005d),
      Opts
        .option[FiniteDuration](
          "historyRetention",
          "how long does older versions of A/B tests be kept"
        )
        .orNone,
      Opts
        .option[Int]("initialSampleSize", "required sample size to start allocating")
        .withDefault(0),
      Opts
        .option[BigDecimal]("maintainExploration", "maintain an exploration size")
        .orNone,
      Opts
        .option[FiniteDuration]("iterationDuration", "duration of each iteration")
        .orNone,
      conversionSettingsOps
    ).mapN(BanditSettings.apply[BanditSettings.Conversion])

  private val banditSpecOpts =
    (
      Opts.options[String]("arms", "list of arms", "a").map(_.toList),
      Opts.option[OffsetDateTime]("start", "start time of the MAB"),
      banditSettingsOps
    ).mapN(BanditSpec.apply[BanditSettings.Conversion])

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
              client.updatePolicy(feature)
            }
          }
        }
      )
    )
}
