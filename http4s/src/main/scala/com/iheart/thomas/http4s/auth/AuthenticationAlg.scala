package com.iheart.thomas
package http4s
package auth

import cats.effect.Concurrent
import com.iheart.thomas.dynamo
import com.iheart.thomas.admin.{Role, User, UserDAO}
import tsec.authentication.Authenticator
import tsec.passwordhashers.{PasswordHash, PasswordHasher}
import cats.implicits._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import com.iheart.thomas.http4s.auth.AuthError._
import org.http4s.Response
import tsec.common.Verified
import tsec.passwordhashers.jca.BCrypt
import cats.MonadThrow
import scala.util.control.NoStackTrace
trait AuthenticationAlg[F[_], Auth] {

  def login(
      username: Username,
      password: String,
      respond: User => F[Response[F]]
    ): F[Response[F]]

  def register(
      username: Username,
      password: String,
      role: Role
    ): F[User]

  def update(
      username: Username,
      passwordO: Option[String],
      roleO: Option[Role]
    ): F[User]

  def remove(username: String): F[Unit]

  def logout(token: Token[Auth]): F[Unit]
  def allUsers: F[Vector[User]]
}

object AuthenticationAlg {

  implicit def apply[F[_]: MonadThrow, C, Auth](
      implicit userDAO: UserDAO[F],
      cryptService: PasswordHasher[F, C],
      auth: Authenticator[F, String, User, Token[Auth]]
    ): AuthenticationAlg[F, Auth] =
    new AuthenticationAlg[F, Auth] {
      def logout(token: Token[Auth]): F[Unit] = {
        auth.discard(token).void
      }
      def login(
          username: Username,
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
          username: Username,
          password: String,
          role: Role
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
          username: Username,
          passwordO: Option[String],
          roleO: Option[Role]
        ): F[User] =
        for {
          user <- userDAO.get(username)
          _ <- roleO.fold(().pure[F]) { newRole =>
            val demoting =
              user.role == Role.Admin && newRole != Role.Admin
            if (demoting)
              userDAO.all
                .ensure(MustHaveAtLeastOneAdmin)(_.count(_.role == Role.Admin) > 1)
                .void
            else ().pure[F]
          }
          hO <- passwordO.traverse(cryptService.hashpw)
          update =
            user
              .copy(
                hash = hO.getOrElse(user.hash),
                role = roleO.getOrElse(user.role)
              )
          u <- userDAO.update(update)
        } yield u

      def remove(username: Username): F[Unit] = userDAO.remove(username)

      def allUsers: F[Vector[User]] = userDAO.all
    }

  /**
    * using dynamo BCyrpt and HMACSHA256
    */
  def default[F[_]: Concurrent](
      key: String
    )(implicit dc: DynamoDbAsyncClient
    ): F[AuthenticationAlg[F, AuthImp]] =
    AuthDependencies[F](key).map { deps =>
      import dynamo.AdminDAOs._
      import BCrypt._
      import deps._
      implicitly[AuthenticationAlg[F, AuthImp]]
    }
}

sealed abstract class AuthError extends RuntimeException with NoStackTrace

object AuthError {
  case class UserNotFound(username: String) extends AuthError
  case class UserAlreadyExist(username: String) extends AuthError
  case class PasswordTooWeak(username: String) extends AuthError
  case object IncorrectPassword extends AuthError
  case object MustHaveAtLeastOneAdmin extends AuthError
}
