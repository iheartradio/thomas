package com.iheart.thomas.cli


import cats.Alternative
import cats.data.NonEmptyList
import com.monovore.decline.{Command, Opts}
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._

object Main extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    val gmCmd = new GroupMetaCommands[IO].groupMetaCommand

    gmCmd.parse(args).fold(
      help => IO(System.err.println(help)).as(ExitCode.Error),
      _.as(ExitCode.Success)
    )
  }


  def subCommands[A](commands: Command[A]*): Opts[A] = {
    implicit val m = Alternative[Opts].algebra[A]
    NonEmptyList.fromListUnsafe(commands.toList).map(Opts.subcommand(_)).reduce
  }
}
