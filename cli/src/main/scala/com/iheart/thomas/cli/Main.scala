package com.iheart.thomas
package cli

import com.monovore.decline.{Command, Opts}
import cats.effect.{ExitCode, IO, IOApp}

import concurrent.ExecutionContext.Implicits.global
object Main extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    val cmd = Command("thomas", "Thomas cli")(
      Opts.subcommands(
        new GroupMetaCommands[IO].groupMetaCommand,
        new EligibilityControlCommand[IO].userMetaCriteriaCommand,
        BayesianMABCommands.conversionBMABCommand[IO]
      )
    )

    IO(
      System.out.print(
        util.Random
          .shuffle(logos)
          .head + s"\n${BuildInfo.name} v${BuildInfo.version}\n\n"
      )
    ) *>
      cmd
        .parse(args)
        .fold(
          help => IO(System.err.println(help)).as(ExitCode.Error),
          _.as(ExitCode.Success)
        )
  }

  val logos = Seq(
    """
      |    .....                                                                     .x+=:.
      | .H8888888h.  ~-.    .uef^"                                                  z`    ^%
      | 888888888888x  `> :d88E              u.      ..    .     :                     .   <k
      |X~     `?888888hx~ `888E        ...ue888b   .888: x888  x888.        u        .@8Ned8"
      |'      x8.^"*88*"   888E .z8k   888R Y888r ~`8888~'888X`?888f`    us888u.   .@^%8888"
      | `-:- X8888x        888E~?888L  888R I888>   X888  888X '888>  .@88 "8888" x88:  `)8b.
      |      488888>       888E  888E  888R I888>   X888  888X '888>  9888  9888  8888N=*8888
      |    .. `"88*        888E  888E  888R I888>   X888  888X '888>  9888  9888   %8"    R88
      |  x88888nX"      .  888E  888E u8888cJ888    X888  888X '888>  9888  9888    @8Wou 9%
      | !"*8888888n..  :   888E  888E  "*888*P"    "*88%""*88" '888!` 9888  9888  .888888P`
      |'    "*88888888*   m888N= 888>    'Y"         `~    "    `"`   "888*""888" `   ^"F
      |        ^"***"`     `Y"   888                                   ^Y"   ^Y'
      |                         J88"
      |                         @%
      |                       :"
    """.stripMargin,
    """
      t  _____    _
      t |_   _|  | |_       ___     _ __     __ _      ___
      t   | |    | ' \     / _ \   | '  \   / _` |    (_-<
      t  _|_|_   |_||_|    \___/   |_|_|_|  \__,_|    /__/_
      t_|'''''| _|'''''| _|'''''| _|'''''| _|'''''| _|'''''|
      t"`-0-0-' "`-0-0-' "`-0-0-' "`-0-0-' "`-0-0-' "`-0-0-'
      t
    """.stripMargin('t')
  )

}
