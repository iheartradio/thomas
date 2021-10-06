package com.iheart.thomas.http4s

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.iheart.thomas.admin.Role
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

class AdminUIConfigSuite extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  "AdminUIConfig" - {
    "can read reference conf" in {
      ConfigResource
        .cfg[IO]()
        .map(AdminUI.loadConfig[IO](_))
        .use(identity)
        .asserting(cfg => Role.values.contains(cfg.initialRole) shouldBe true)
    }
  }
}
