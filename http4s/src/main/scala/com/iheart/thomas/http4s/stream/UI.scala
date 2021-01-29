package com.iheart.thomas.http4s.stream

import cats.effect.Async
import com.iheart.thomas.admin
import com.iheart.thomas.http4s.{AuthImp, ReverseRoutes}
import com.iheart.thomas.http4s.auth.{AuthedEndpointsUtils, AuthenticationAlg}
import com.iheart.thomas.stream.JobAlg
import org.http4s.dsl.Http4sDsl
import tsec.authentication._
import cats.implicits._
import com.iheart.thomas.html.redirect
import org.http4s.twirl._
import com.iheart.thomas.stream.html._

class UI[F[_]: Async](
    implicit
    jobAlg: JobAlg[F],
    authAlg: AuthenticationAlg[F, AuthImp],
    reverseRoutes: ReverseRoutes)
    extends AuthedEndpointsUtils[F, AuthImp]
    with Http4sDsl[F] {

  val rootPath = Root / "stream"

  val readonlyRoutes = roleBasedService(admin.Authorization.backgroundManagerRoles) {
    case GET -> `rootPath` / "background" asAuthed u => {
      jobAlg.allJobs.flatMap { jobs =>
        Ok(background(jobs, u))
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
