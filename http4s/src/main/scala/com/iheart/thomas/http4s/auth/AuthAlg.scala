package com.iheart.thomas.http4s
package auth

import cats.effect.Concurrent
import com.iheart.thomas.{MonadThrowable, dynamo}
import com.iheart.thomas.admin.{Role, User, UserDAO}
import tsec.authentication.{Authenticator}
import tsec.passwordhashers.{PasswordHash, PasswordHasher}
import cats.implicits._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.http4s.auth.AuthError.UserAlreadyExist
import org.http4s.Response
import tsec.common.Verified
import tsec.mac.jca.HMACSHA256
import tsec.passwordhashers.jca.BCrypt

import scala.util.control.NoStackTrace
trait AuthAlg[F[_], Auth] {

  def login(
      username: String,
      password: String,
      respond: User => F[Response[F]]
    ): F[Response[F]]

  def register(
      username: String,
      password: String,
      role: Role = Roles.Reader
    ): F[User]

  def update(
      username: String,
      passwordO: Option[String],
      roleO: Option[Role]
    ): F[User]

  def remove(username: String): F[Unit]

  def logout(token: Token[Auth]): F[Unit]
  def allUsers: F[Vector[User]]
}

object AuthAlg {

  implicit def apply[F[_]: MonadThrowable, C, Auth](
      implicit userDAO: UserDAO[F],
      cryptService: PasswordHasher[F, C],
      auth: Authenticator[F, String, User, Token[Auth]]
    ): AuthAlg[F, Auth] =
    new AuthAlg[F, Auth] {
      def logout(token: Token[Auth]): F[Unit] = {
        auth.discard(token).void
      }
      def login(
          username: String,
          password: String,
          respond: User => F[Response[F]]
        ): F[Response[F]] =
        for {
          userO <- userDAO.find(username)
          user <- userO.liftTo[F](AuthError.UserNotFound(username))
          _ <-
            cryptService
              .checkpw(password, PasswordHash[C](user.hash))
              .ensure(AuthError.IncorrectPassword)(_ == Verified)
          token <- auth.create(user.username)
          response <- respond(user)
        } yield auth.embed(response, token)

      def register(
          username: String,
          password: String,
          role: Role = Roles.Reader
        ): F[User] = {
        userDAO.find(username).ensure(UserAlreadyExist(username))(_.isEmpty) *>
          password
            .pure[F]
            .ensure(AuthError.PasswordTooWeak(username))(_.length > 5) *>
          cryptService
            .hashpw(password)
            .flatMap { h =>
              userDAO.insert(User(username, h, role))
            }
      }

      def update(
          username: String,
          passwordO: Option[String],
          roleO: Option[Role]
        ): F[User] =
        for {
          user <- userDAO.get(username)
          hO <- passwordO.traverse(cryptService.hashpw)
          update =
            user
              .copy(
                hash = hO.getOrElse(user.hash),
                role = roleO.getOrElse(user.role)
              )
          u <- userDAO.update(update)
        } yield u

      def remove(username: String): F[Unit] = userDAO.remove(username)

      def allUsers: F[Vector[User]] = userDAO.all
    }

  /**
    * using dynamo BCyrpt and HMACSHA256
    */
  def default[F[_]: Concurrent](
      key: String
    )(implicit dc: AmazonDynamoDBAsync
    ): F[AuthAlg[F, HMACSHA256]] =
    AuthDependencies[F](key).map { deps =>
      import dynamo.AdminDAOs._
      import BCrypt._
      import deps._
      implicitly[AuthAlg[F, HMACSHA256]]
    }
}

sealed abstract class AuthError extends RuntimeException with NoStackTrace

object AuthError {
  case class UserNotFound(username: String) extends AuthError
  case class UserAlreadyExist(username: String) extends AuthError
  case class PasswordTooWeak(username: String) extends AuthError
  case object IncorrectPassword extends AuthError
}
