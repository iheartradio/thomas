package com.iheart.thomas
package cli

import cats.data.{Validated, ValidatedNel}
import cats.effect.ConcurrentEffect
import cats.implicits._
import com.iheart.thomas.bandit.BanditSpec
import com.iheart.thomas.bandit.Formats._
import com.iheart.thomas.bandit.bayesian.BanditSettings
import com.iheart.thomas.cli.BayesianBanditHttpClientOpts.conversionClientOpts
import com.iheart.thomas.cli.OptsSyntax._
import com.monovore.decline.{Argument, Command, Opts}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

object BayesianMABCommands {
  val fnOpts = Opts.option[String]("feature", "feature name", "f")

  implicit val durationArg: Argument[FiniteDuration] = new Argument[FiniteDuration] {

    override def read(string: String): ValidatedNel[String, FiniteDuration] =
      Validated
        .fromTry(Try(Duration(string).asInstanceOf[FiniteDuration]))
        .leftMap(_.getMessage)
        .toValidatedNel

    override def defaultMetavar: String = "duration string"
  }

  private val banditSpecOpts = Opts
    .option[String](
      "banditSpecFile",
      "the location of the file containing the json file of the bandit spec"
    )
    .readJsonFile[BanditSpec[BanditSettings.Conversion]]

  def conversionBMABCommand[F[_]](
      implicit F: ConcurrentEffect[F],
      ec: ExecutionContext
    ): Command[F[String]] =
    Command(
      "conversionBMAB",
      "manage conversion based Bayesian Multi Arm Bandits"
    )(
      Opts.subcommands(
        Command("init", "init a new conversion KPI Bayesian MAB") {
          (banditSpecOpts, conversionClientOpts[F]).mapN { (spec, clientR) =>
            clientR.use { client =>
              client.init(spec).map(b => s"Successfully created $b")
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
                .map(s => s"""
                      |=========== Bayesian State Start ============
                      |$s
                      |=========== Bayesian State End =============
                      |""".stripMargin)
            }
          }
        },
        Command(
          "reallocate",
          "show an existing conversion KPI based Bayesian MAB"
        ) {
          (fnOpts, conversionClientOpts[F]).mapN { (feature, clientR) =>
            clientR.use { client =>
              client.updatePolicy(feature).as(s"Policy for $feature is updated")
            }
          }
        }
      )
    )
}
