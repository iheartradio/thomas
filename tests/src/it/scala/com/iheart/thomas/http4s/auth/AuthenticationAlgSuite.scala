package com.iheart.thomas.http4s.auth

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.iheart.thomas.admin.Role
import com.iheart.thomas.http4s.auth.AuthError.{IncorrectPassword, InvalidToken}
import com.iheart.thomas.testkit.Resources
import org.http4s.Status
import org.http4s.dsl.Http4sDsl
import org.scalatest.matchers.should.Matchers

class AuthenticationAlgSuite extends AsyncIOSpec with Matchers with Http4sDsl[IO] {
  val secret =
    "cbefc59aec711816e112fbb0dc5335b3dd41e57f9b5ed8e8f2a601bc78bd054429f9dba611d1d4955a2003f80d6ff1b515135ffb1cdad3a28d71996f0c76e3420c39cbfd7ae2f2abfb99aecef069f12baf64c5bbe5001193ff28b428bb87b403627776e674d7e5ef4133f8bfb26d5cc0234ad50f69fec6467d5dd1d33d990ed29a59488cb59f060e7fc09b4f2c5ba6bd7a4a03bfa15cef5591497bcc91e98711243c7cb6fc9c302ffa3f3f36131ec31f239c26ca12a81efd850052c72106f6019eca1c5b1f238755cf85626cb49e70f33774412dfb296cbeda96e27afbab613c27438b92e1ef00c15e048f72eb6be5072402ece10d7d45de83ceef3abc0bc8c1"

  val algR = Resources.localDynamoR.evalMap { implicit lc =>
    AuthenticationAlg.default[IO](secret)
  }

  "AuthAlg" - {
    "register user and find him" in {
      algR
        .use { alg =>
          alg.register("tom", "password1", Role.Admin) *> alg.allUsers
        }
        .asserting {
          _.exists(_.username == "tom") shouldBe true
        }

    }

    "register user and login" in {
      algR
        .use { alg =>
          alg.register("tom", "password1", Role.Admin) *>
            alg.login("tom", "password1", _ => Ok("logged In"))
        }
        .asserting(_.status shouldBe Status.Ok)
    }

    "throw error when login incorrect password" in {
      algR
        .use { alg =>
          alg.register("tom", "password1", Role.Admin) *>
            alg.login("tom", "wrongpassword", _ => Ok("logged In"))
        }
        .assertThrows[IncorrectPassword.type]
    }

    "reset password success" in {
      algR
        .use { alg =>
          alg.register("tom", "password1", Role.Admin) *>
            (for {
              token <- alg.generateResetToken("tom")
              _ <- alg.resetPass("password2", token.value, "tom")
              login <-
                alg.login("tom", "password2", _ => Ok("logged in with new pass"))
            } yield login)
        }
        .asserting(_.status shouldBe Status.Ok)
    }

    "reset password fail on incorrect token" in {
      algR
        .use { alg =>
          alg.register("tom", "password1", Role.Admin) *>
            alg.generateResetToken("tom") *>
            alg.resetPass("password2", "wrong_token", "tom")
        }
        .assertThrows[InvalidToken.type]
    }
    "reset password fail on reusing token" in {
      algR
        .use { alg =>
          alg.register("tom", "password1", Role.Admin) *>
            (for {
              token <- alg.generateResetToken("tom")
              _ <- alg.resetPass("password2", token.value, "tom")
              _ <- alg.resetPass("password3", token.value, "tom")
            } yield ())
        }
        .assertThrows[InvalidToken.type]
    }
  }
}
