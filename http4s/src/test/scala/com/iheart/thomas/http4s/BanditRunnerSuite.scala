package com.iheart.thomas.http4s

import cats.effect.IO
import cats.effect.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import cats.implicits._
import concurrent.duration._

class BanditRunnerSuite extends AsyncIOSpec with Matchers {

  "BanditRunner periodical tasks"
  "play" in {

    (for {
      runner <- BanditRunner.default[IO]
      firstStart <- runner.startPeriodicalReallocation
      _ <- runner.stopPeriodicalReallocation
    } yield firstStart)
      .asserting(_ shouldBe true)

  }
}
