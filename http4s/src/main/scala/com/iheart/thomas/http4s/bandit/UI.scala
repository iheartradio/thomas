package com.iheart.thomas
package http4s
package bandit

import cats.effect.Async
import com.iheart.thomas.bandit.html._
import com.iheart.thomas.bandit.bayesian.BayesianMABAlg
import com.iheart.thomas.http4s.AdminUI.AdminUIConfig
import com.iheart.thomas.http4s.auth.AuthedEndpointsUtils
import org.http4s.dsl.Http4sDsl
import tsec.authentication.asAuthed
import cats.implicits._
import org.http4s.twirl._

class UI[F[_]: Async](implicit alg: BayesianMABAlg[F], aCfg: AdminUIConfig)
    extends AuthedEndpointsUtils[F, AuthImp]
    with Http4sDsl[F] {
//  val reverseRoutes = ReverseRoutes(aCfg)

  val rootPath = Root / "bandits"

  val routes = roleBasedService(admin.Authorization.banditsManagerRoles) {

    case GET -> `rootPath` / "" asAuthed (u) =>
      for {
        bandits <- alg.getAll
        r <- Ok(index(bandits)(UIEnv(u)))
      } yield r

    case GET -> `rootPath` / "new" / "form" asAuthed (u) =>
      Ok(newBandit(UIEnv(u)))

  }
}

object UI {
  object decoders {}
}
