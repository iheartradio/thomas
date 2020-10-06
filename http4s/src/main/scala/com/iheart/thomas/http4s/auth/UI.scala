package com.iheart.thomas
package http4s
package auth
import cats.effect.{Async, Concurrent}
import cats.implicits._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.auth.html

import com.iheart.thomas.html.redirect
import com.iheart.thomas.http4s.auth.UI.QueryParamMatchers._
import com.iheart.thomas.http4s.auth.UI.ValidationErrors._
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import org.http4s.twirl._
import org.http4s.{HttpRoutes, Uri, UrlForm}
import tsec.common.SecureRandomIdGenerator
import tsec.mac.jca.HMACSHA256
import tsec.passwordhashers.jca.BCrypt

import scala.util.control.NoStackTrace

class UI[F[_]: Async, Auth](
    implicit alg: AuthAlg[F, Auth],
    requestHandler: AuthedRequestHandler[F, Auth],
    reverseRoutes: ReverseRoutes)
    extends Http4sDsl[F]
    with AuthedEndpointsUtils[F, Auth] {
  import tsec.authentication._

  val userManagementEndpoints = roleBasedService(Roles.Admin) {
    case GET -> Root / "users" asAuthed user =>
      alg.allUsers.flatMap { allUsers =>
        Ok(html.users(allUsers, user))
      }
  }

  val logout = roleBasedService(Roles.values) {
    case req @ GET -> Root / "logout" asAuthed _ =>
      alg.logout(req.authenticator) *>
        Ok(html.login()).map(_.removeCookie(AuthDependencies.tokenCookieName))
  }

  val userPublic = HttpRoutes.of[F] {
    case GET -> Root / "login" =>
      Ok(html.login())

    case req @ POST -> Root / "login" :? redirectTo(to) =>
      for {
        form <- req.as[UrlForm]
        username <- form.getFirst("username").liftTo[F](MissingUsername)
        password <- form.getFirst("password").liftTo[F](MissingPassword)
        r <- alg.login(
          username,
          password,
          u =>
            Ok(
              redirect(
                to.map(_.renderString).getOrElse(reverseRoutes.users),
                "Login successfully!",
                100
              )
            )
        )
      } yield r

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
          password
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
      }

  }

  val routes: HttpRoutes[F] =
    userPublic <+> liftService(userManagementEndpoints <+> logout)
}

object UI extends {
  object ValidationErrors {
    case object MissingUsername extends RuntimeException with NoStackTrace
    case object MissingPassword extends RuntimeException with NoStackTrace
    case class MismatchingPassword(usaername: String)
        extends RuntimeException
        with NoStackTrace
  }

  object QueryParamMatchers {
    object redirectTo extends OptionalQueryParamDecoderMatcher[Uri]("redirectTo")
  }

  /**
    * using dynamo BCyrpt and HMACSHA256
    * @param key
    * @param dc
    * @tparam F
    * @return
    */
  def default[F[_]: Concurrent](
      key: String,
      rootPath: String,
      readCapacity: Long,
      writeCapacity: Long
    )(implicit dc: AmazonDynamoDBAsync
    ): F[UI[F, HMACSHA256]] = {
    import dynamo.AdminDAOs._

    ensureAuthTables[F](readCapacity, writeCapacity) *> {
      AuthDependencies[F](key).map { deps =>
        import BCrypt._
        import deps._
        import dynamo.AdminDAOs._
        implicit val rv = new ReverseRoutes(rootPath)
        new UI
      }
    }

  }

  def generateKey: String =
    SecureRandomIdGenerator(256).generate
}
