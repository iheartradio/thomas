package com.iheart.thomas
package http4s
package auth

import cats.Applicative
import cats.data.{Kleisli, OptionT}
import com.iheart.thomas.MonadThrowable
import com.iheart.thomas.admin.{Role, User}
import com.iheart.thomas.http4s.{ReverseRoutes, Roles}
import org.http4s.{HttpRoutes, Response}
import org.http4s.dsl.Http4sDsl
import tsec.authentication.{SecuredRequest, SecuredRequestHandler, TSecAuthService}
import tsec.authorization.{AuthGroup, AuthorizationInfo, BasicRBAC}
import org.http4s.twirl._
import cats.implicits._
trait AuthedEndpointsUtils[F[_], Auth] {
  self: Http4sDsl[F] =>

  type AuthService = TSecAuthService[User, Token[Auth], F]

  type AuthEndpoint =
    PartialFunction[SecuredRequest[F, User, Token[Auth]], F[
      Response[F]
    ]]

  type AuthReqHandler = SecuredRequestHandler[
    F,
    String,
    User,
    Token[Auth]
  ]

  implicit def authorizationInfoForUserRole(
      implicit F: Applicative[F]
    ): AuthorizationInfo[F, Role, User] =
    (u: User) => F.pure(u.role)

  def liftService(
      service: AuthService
    )(implicit arh: AuthReqHandler,
      reverseRoutes: ReverseRoutes,
      F: Applicative[F]
    ): HttpRoutes[F] =
    arh.liftService(
      service,
      req =>
        Ok(
          html.redirect(
            reverseRoutes.login + "?redirectTo=" + req.uri.renderString,
            s"Please login first. " + req.headers.toString
          )
        )
    )

  def roleBasedService(
      authGroup: AuthGroup[Role]
    )(pf: AuthEndpoint
    )(implicit
      F: MonadThrowable[F],
      reverseRoutes: ReverseRoutes
    ): AuthService = {
    val auth = BasicRBAC.fromGroup[F, Role, User, Token[Auth]](authGroup)
    val onUnauthorized = Ok(
      html.redirect(
        reverseRoutes.login,
        s"Sorry, you do not have sufficient access."
      )
    )

    Kleisli { req: SecuredRequest[F, User, Token[Auth]] =>
      if (pf.isDefinedAt(req)) {
        OptionT(
          auth
            .isAuthorized(req)
            .fold(onUnauthorized)(pf)
            .flatten
            .map(Option(_))
        )
      } else
        OptionT.none[F, Response[F]]

    }
  }

  def roleBasedService(
      role: Role*
    )(pf: AuthEndpoint
    )(implicit
      F: MonadThrowable[F],
      reverseRoutes: ReverseRoutes
    ): AuthService = roleBasedService(AuthGroup(role: _*))(pf)

}
