package com.iheart.thomas
package http4s.auth

import cats.data.OptionT
import cats.effect.Sync
import cats.implicits._
import com.iheart.thomas.{MonadThrowable, Username}
import com.iheart.thomas.admin.{AuthRecord, AuthRecordDAO, User, UserDAO}
import com.iheart.thomas.http4s.AuthImp
import com.iheart.thomas.http4s.auth.AuthDependencies.tokenCookieName
import tsec.authentication.{
  AugmentedJWT,
  Authenticator,
  BackingStore,
  IdentityStore,
  JWTAuthenticator,
  TSecCookieSettings
}
import tsec.common.SecureRandomId
import tsec.jws.JWSSerializer
import tsec.jws.mac.{JWSMacCV, JWSMacHeader, JWTMacImpure}
import tsec.jwt.algorithms.JWTMacAlgo
import tsec.mac.jca.{HMACSHA256, MacErrorM, MacSigningKey}

import concurrent.duration._

class AuthDependencies[A](key: MacSigningKey[A]) {
  type Token = AugmentedJWT[A, Username]

  implicit def backingStore[F[_]: MonadThrowable](
      implicit dao: AuthRecordDAO[F],
      hs: JWSSerializer[JWSMacHeader[A]],
      s: JWSMacCV[MacErrorM, A]
    ): BackingStore[F, SecureRandomId, Token] =
    new BackingStore[F, SecureRandomId, Token] {

      implicit def toRecord(jwt: Token) = {
        AuthRecord(
          id = jwt.id,
          jwtEncoded = jwt.jwt.toEncodedString,
          identity = jwt.identity,
          expiry = jwt.expiry,
          lastTouched = jwt.lastTouched
        )
      }

      def put(elem: Token): F[Token] = {
        dao.insert(elem).as(elem)
      }

      def update(v: Token): F[Token] = {

        dao.update(v).as(v)
      }

      def delete(id: SecureRandomId): F[Unit] = dao.remove(id)

      def get(id: SecureRandomId): OptionT[F, Token] = {
        OptionT(dao.find(id)).semiflatMap {
          case AuthRecord(_, jwtStringify, identity, expiry, lastTouched) =>
            JWTMacImpure.verifyAndParse(jwtStringify, key) match {
              case Left(err) => err.raiseError[F, Token]
              case Right(jwt) =>
                AugmentedJWT(id, jwt, identity, expiry, lastTouched).pure[F]
            }
        }
      }
    }

  implicit def identityStore[F[_]](
      implicit dao: UserDAO[F]
    ): IdentityStore[F, Username, User] =
    (id: String) => OptionT(dao.find(id))

  implicit def jwtAuthenticator[F[_]: Sync](
      implicit authRepo: BackingStore[F, SecureRandomId, Token],
      userRepo: IdentityStore[F, Username, User],
      cv: JWSMacCV[F, A],
      A: JWTMacAlgo[A]
    ): Authenticator[F, Username, User, Token] =
    JWTAuthenticator.backed.inCookie(
      TSecCookieSettings(
        cookieName = tokenCookieName,
        secure = false,
        expiryDuration = 1.days,
        maxIdle = None
      ),
      tokenStore = authRepo,
      identityStore = userRepo,
      signingKey = key
    )

}

object AuthDependencies {
  val tokenCookieName = "thomas-token"
  import tsec.common._
  def apply[F[_]: Sync](key: String): F[AuthDependencies[AuthImp]] =
    key.hexBytes.flatMap(HMACSHA256.buildKey[F]).map(new AuthDependencies(_))

}
