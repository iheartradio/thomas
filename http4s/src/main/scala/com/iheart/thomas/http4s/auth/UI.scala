package com.iheart.thomas
package http4s
package auth
import cats.effect.Async
import cats.implicits._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.admin.Role
import com.iheart.thomas.auth.html
import com.iheart.thomas.html.redirect
import com.iheart.thomas.http4s.auth.UI.QueryParamMatchers._
import com.iheart.thomas.http4s.auth.UI.ValidationErrors._
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import org.http4s.twirl._
import org.http4s.{HttpRoutes, Uri, UrlForm}
import tsec.passwordhashers.jca.BCrypt

import scala.util.control.NoStackTrace

class UI[F[_]: Async, Auth](
    initialAdminUsername: Option[String],
    initialRole: Role
  )(implicit alg: AuthAlg[F, Auth],
    reverseRoutes: ReverseRoutes)
    extends Http4sDsl[F]
    with AuthedEndpointsUtils[F, Auth] {
  import tsec.authentication._

  val authedService = roleBasedService(Roles.Admin) {
    case GET -> Root / "users" asAuthed user =>
      alg.allUsers.flatMap { allUsers =>
        Ok(html.users(allUsers, user))
      }
  } <+> roleBasedService(Roles.values) {
    case req @ GET -> Root / "logout" asAuthed _ =>
      alg.logout(req.authenticator) *>
        redirectTo(reverseRoutes.login)
          .map(_.removeCookie(AuthDependencies.tokenCookieName))
  }

  val publicEndpoints = HttpRoutes.of[F] {
    case GET -> Root / "login" =>
      Ok(html.login())

    case req @ POST -> Root / "login" :? redirectTo(to) =>
      (for {
        form <- req.as[UrlForm]
        username <- form.getFirst("username").liftTo[F](MissingUsername)
        password <- form.getFirst("password").liftTo[F](MissingPassword)
        r <- alg.login(
          username,
          password,
          _ =>
            Ok(
              redirect(
                to.map(_.renderString).getOrElse(reverseRoutes.tests),
                "Login Successful"
              )
            )
        )
      } yield r).recoverWith {
        case AuthError.IncorrectPassword =>
          BadRequest(
            html.login(Some("Incorrect Password!"))
          )
        case AuthError.UserNotFound(username) =>
          BadRequest(
            html.login(Some(s"Username $username is not found"))
          )
      }

    case GET -> Root / "register" =>
      Ok(html.registration())

    case req @ POST -> Root / "register" =>
      (for {
        form <- req.as[UrlForm]
        username <- form.getFirst("username").liftTo[F](MissingUsername)
        password <- form.getFirst("password").liftTo[F](MissingPassword)
        cfmPassword <- form.getFirst("cfmPassword").liftTo[F](MissingPassword)
        _ <-
          (password == cfmPassword)
            .guard[Option]
            .liftTo[F](MismatchingPassword(username))
        _ <- alg.register(
          username,
          password,
          if (initialAdminUsername.fold(false)(_ == username)) Roles.Admin
          else initialRole
        )
        r <- Ok(
          redirect(
            reverseRoutes.login,
            s"User $username is registered. Redirecting to login."
          )
        )
      } yield r).recoverWith {
        case MismatchingPassword(username) =>
          BadRequest(
            html.registration(Some("The passwords don't match!"), Some(username))
          )
        case AuthError.UserAlreadyExist(username) =>
          BadRequest(
            html.registration(Some(s"The username $username is already taken."))
          )
        case AuthError.PasswordTooWeak(username) =>
          BadRequest(
            html.registration(
              Some(s"Please select a stronger password"),
              Some(username)
            )
          )
      }

  }

}

object UI extends {
  object ValidationErrors {
    case object MissingUsername extends RuntimeException with NoStackTrace
    case object MissingPassword extends RuntimeException with NoStackTrace
    case class MismatchingPassword(username: String)
        extends RuntimeException
        with NoStackTrace
  }

  object QueryParamMatchers {
    object redirectTo extends OptionalQueryParamDecoderMatcher[Uri]("redirectTo")
  }

  /**
    * @return
    */
  def default[F[_]: Async](
      authDeps: AuthDependencies[AuthImp],
      initialAdminUsername: Option[String],
      initialRole: Role
    )(implicit dc: AmazonDynamoDBAsync,
      rv: ReverseRoutes
    ): UI[F, AuthImp] = {

    import BCrypt._
    import authDeps._
    import dynamo.AdminDAOs._

    new UI(initialAdminUsername, initialRole)
  }

}
