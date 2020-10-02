package com.iheart.thomas
package http4s.auth
import cats.effect.{Async, Concurrent}
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.auth.html.{login, registration}
import org.http4s.{HttpRoutes, Uri, UrlForm}
import org.http4s.dsl.Http4sDsl
import org.http4s.twirl._
import tsec.mac.jca.HMACSHA256
import cats.implicits._
import tsec.common.SecureRandomIdGenerator
import UI.ValidationErrors._
import com.iheart.thomas.html.redirect
import UI.QueryParamMatchers._
import com.iheart.thomas.http4s.ReverseRoutes
import org.http4s.dsl.impl.{OptionalQueryParamDecoderMatcher}

import scala.util.control.NoStackTrace

class UI[F[_]: Async, Auth](
    implicit alg: AuthAlg[F, Auth],
    reverseRoutes: ReverseRoutes)
    extends Http4sDsl[F] {

  val routes = HttpRoutes.of[F] {
    case GET -> Root / "login" =>
      Ok(login())

    case req @ POST -> Root / "login" :? redirectTo(to) =>
      for {
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
                "Login successfully!"
              )
            )
        )
      } yield r

    case GET -> Root / "register" =>
      Ok(registration())

    case req @ POST -> Root / "register" =>
      (for {
        form <- req.as[UrlForm]
        username <- form.getFirst("username").liftTo[F](MissingUsername)
        password <- form.getFirst("password").liftTo[F](MissingPassword)
        cfmPassword <- form.getFirst("cfmPassword").liftTo[F](MissingPassword)
        _ <- (password == cfmPassword)
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
            registration(Some("The passwords don't match!"), Some(username))
          )
        case AuthError.UserAlreadyExist(username) =>
          BadRequest(registration(Some(s"The username $username is already taken.")))
      }

  }
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

    ensureAuthTables[F](readCapacity, writeCapacity) *> AuthAlg
      .default[F](key)
      .map { implicit alg =>
        implicit val rv = new ReverseRoutes(rootPath)
        new UI
      }

  }

  def generateKey: String =
    SecureRandomIdGenerator(256).generate
}
