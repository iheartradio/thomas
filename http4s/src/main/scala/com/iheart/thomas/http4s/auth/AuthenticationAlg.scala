package com.iheart.thomas
package http4s
package auth

import cats.effect.{Async, Clock}
import com.iheart.thomas.dynamo
import com.iheart.thomas.admin.{PassResetToken, Role, User, UserDAO}
import tsec.authentication.Authenticator
import tsec.passwordhashers.{PasswordHash, PasswordHasher}
import cats.implicits._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import com.iheart.thomas.http4s.auth.AuthError._
import org.http4s.Response
import tsec.common.{SecureRandomIdGenerator, Verified}
import tsec.passwordhashers.jca.BCrypt
import cats.MonadThrow

import scala.util.control.NoStackTrace
import utils.time._
import concurrent.duration._
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

  def updateRole(
      username: Username,
      roleO: Option[Role]
    ): F[User]

  def deleteUser(
      username: Username
    ): F[Unit]

  def remove(username: String): F[Unit]

  def logout(token: Token[Auth]): F[Unit]
  def allUsers: F[Vector[User]]

  def generateResetToken(
      username: Username
    )(implicit T: Clock[F]
    ): F[PassResetToken]

  def resetPass(
      newPass: String,
      token: String,
      username: Username
    )(implicit T: Clock[F]
    ): F[User]
}

object AuthenticationAlg {

  implicit def apply[F[_], C, Auth](
      implicit userDAO: UserDAO[F],
      cryptService: PasswordHasher[F, C],
      auth: Authenticator[F, String, User, Token[Auth]],
      F: MonadThrow[F]
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
          hashPassword(password, AuthError.PasswordTooWeak(username))
            .flatMap { h =>
              userDAO.insert(User(username, h, role))
            }
      }

      private def hashPassword(
          password: String,
          toThrow: AuthError
        ): F[String] =
        password
          .pure[F]
          .ensure(toThrow)(_.length > 5) *>
          cryptService
            .hashpw(password)
            .widen

      def updateRole(
          username: Username,
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
          update =
            user
              .copy(
                role = roleO.getOrElse(user.role)
              )
          u <- userDAO.update(update)
        } yield u

      def remove(username: Username): F[Unit] = userDAO.remove(username)

      def allUsers: F[Vector[User]] = userDAO.all

      def generateResetToken(
          username: Username
        )(implicit T: Clock[F]
        ): F[PassResetToken] =
        for {
          now <- utils.time.now[F]
          token = PassResetToken(
            SecureRandomIdGenerator(32).generate,
            now.plusDuration(24.hours)
          )
          user <- userDAO.get(username)
          _ <-
            userDAO
              .update(
                user.copy(resetToken = Some(token))
              )
        } yield token

      def resetPass(
          newPass: String,
          token: String,
          username: Username
        )(implicit T: Clock[F]
        ): F[User] =
        for {
          user <- userDAO.get(username)
          now <- utils.time.now[F]
          _ <- F.unit.ensure(InvalidToken)(_ =>
            user.resetToken.fold(false)(_.value === token)
          )
          _ <- F.unit.ensure(TokenExpired)(_ =>
            user.resetToken.fold(false)(_.expires.isAfter(now))
          )
          newHash <- hashPassword(newPass, AuthError.PasswordTooWeak(user.username))
          r <- userDAO.update(user.copy(resetToken = None, hash = newHash))
        } yield r

      def deleteUser(username: Username): F[Unit] = userDAO.remove(username)
    }

  /** using dynamo BCyrpt and HMACSHA256
    */
  def default[F[_]: Async](
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
  case object InvalidToken extends AuthError
  case object TokenExpired extends AuthError
  case object MustHaveAtLeastOneAdmin extends AuthError
}
