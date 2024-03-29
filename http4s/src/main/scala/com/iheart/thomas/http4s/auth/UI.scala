package com.iheart.thomas
package http4s
package auth
import cats.effect.Async
import cats.implicits._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import com.iheart.thomas.admin.Role
import com.iheart.thomas.auth.html
import com.iheart.thomas.html.redirect
import com.iheart.thomas.http4s.auth.UI.QueryParamMatchers._
import com.iheart.thomas.http4s.auth.UI.ValidationErrors._
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.impl.{
  OptionalQueryParamDecoderMatcher,
  QueryParamDecoderMatcher
}
import org.http4s.twirl._
import org.http4s.{FormDataDecoder, HttpRoutes, ParseFailure, Uri, UrlForm}
import FormDataDecoder._
import tsec.passwordhashers.jca.BCrypt
import UI.roleFormatter
import com.iheart.thomas.http4s.AdminUI.AdminUIConfig

import scala.util.control.NoStackTrace

class UI[F[_]: Async, Auth](
    initialAdminUsername: Option[String],
    initialRole: Role
  )(implicit alg: AuthenticationAlg[F, Auth],
    adminUICfg: AdminUIConfig)
    extends Http4sDsl[F]
    with AuthedEndpointsUtils[F, Auth] {
  import tsec.authentication._

  val reverseRoutes = implicitly[ReverseRoutes]

  val authedService = roleBasedService(Seq(Role.Admin)) {
    case GET -> Root / "users" asAuthed u =>
      alg.allUsers.flatMap { allUsers =>
        Ok(html.users(allUsers, None)(UIEnv(u)))
      }
    case req @ POST -> Root / "users" / username / "role" asAuthed _ =>
      for {
        role <- req.request.as[Role]
        _ <- alg.updateRole(username, Some(role))
        r <- SeeOther(reverseRoutes.users.location)
      } yield r

    case GET -> Root / "users" / username / "delete" asAuthed _ =>
      alg.deleteUser(username) *>
        SeeOther(reverseRoutes.users.location)

    case req @ GET -> Root / "users" / username / "reset-pass-link" asAuthed (u) =>
      for {
        token <- alg.generateResetToken(username)
        r <- Ok(
          html.resetPassLink(
            username,
            req.request.uri
              .withPath(
                Path.unsafeFromString(s"${reverseRoutes.users}/$username/reset-pass")
              )
              .withQueryParam("token", token.value)
              .toString
          )(UIEnv(u))
        )
      } yield r

  } <+> roleBasedService(Roles.values) {
    case req @ GET -> Root / "logout" asAuthed _ =>
      alg.logout(req.authenticator) *>
        SeeOther(reverseRoutes.login.location)
          .map(_.removeCookie(AuthDependencies.tokenCookieName))
  }

  val publicEndpoints = HttpRoutes.of[F] {
    case GET -> Root / "login" =>
      Ok(html.login())

    case GET -> Root / "users" / username / "reset-pass" :? tokenP(_) =>
      Ok(html.resetPass(username))

    case req @ POST -> Root / "users" / username / "reset-pass" :? tokenP(token) =>
      req.as[UrlForm].flatMap { uf =>
        val passwordO = uf.getFirst("password")
        if (passwordO != uf.getFirst("password2"))
          BadRequest(html.resetPass(username, Some("Passwords don't match.")))
        else
          alg.resetPass(passwordO.getOrElse(""), token, username) *> Ok(
            redirect(reverseRoutes.login, "Password reset successfully")
          )
      }

    case req @ POST -> Root / "login" :? redirectToP(to) =>
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
                to.map(_.renderString).getOrElse(reverseRoutes.home),
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
          if (initialAdminUsername.fold(false)(_ == username))
            Role.Admin // todo: this logic might be too critical to leave in UI
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

  implicit val roleFormatter: FormDataDecoder[Role] =
    field[String]("name").mapValidated(s =>
      Roles
        .fromRepr(s)
        .leftMap(_ => ParseFailure(s"invalid role $s", ""))
        .toValidatedNel
    )

  object QueryParamMatchers {
    object redirectToP extends OptionalQueryParamDecoderMatcher[Uri]("redirectTo")
    object tokenP extends QueryParamDecoderMatcher[String]("token")
  }

  /** @return
    */
  def default[F[_]: Async](
      authDeps: AuthDependencies[AuthImp],
      initialAdminUsername: Option[String],
      initialRole: Role
    )(implicit dc: DynamoDbAsyncClient,
      adminUIConfig: AdminUIConfig
    ): UI[F, AuthImp] = {

    import BCrypt._
    import authDeps._
    import dynamo.AdminDAOs._

    new UI(initialAdminUsername, initialRole)
  }

}
