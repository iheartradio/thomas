package com.iheart.thomas.http4s.stream

import cats.effect.Async
import com.iheart.thomas.admin
import com.iheart.thomas.http4s.{AuthImp, ReverseRoutes, UIEnv}
import com.iheart.thomas.http4s.auth.AuthedEndpointsUtils
import com.iheart.thomas.stream.JobAlg
import org.http4s.dsl.Http4sDsl
import tsec.authentication._
import cats.implicits._
import com.iheart.thomas.html.redirect
import com.iheart.thomas.http4s.AdminUI.AdminUIConfig
import org.http4s.twirl._
import com.iheart.thomas.stream.html._

class UI[F[_]: Async](
    implicit
    jobAlg: JobAlg[F],
    adminUICfg: AdminUIConfig)
    extends AuthedEndpointsUtils[F, AuthImp]
    with Http4sDsl[F] {

  val rootPath = Root / "stream"
  val reverseRoutes = implicitly[ReverseRoutes]

  val readonlyRoutes = roleBasedService(admin.Authorization.backgroundManagerRoles) {
    case GET -> `rootPath` / "background" asAuthed u => {
      jobAlg.allJobs.flatMap { jobs =>
        Ok(background(jobs)(UIEnv(u)))
      }
    }

    case GET -> `rootPath` / "background" / jobKey / "stop" asAuthed _ => {
      jobAlg.stop(jobKey) *> Ok(
        redirect(
          reverseRoutes.background,
          s"Process $jobKey is stopped."
        )
      )
    }
  }

  val routes = readonlyRoutes

}
