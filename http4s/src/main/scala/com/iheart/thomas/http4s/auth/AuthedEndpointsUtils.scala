package com.iheart.thomas
package http4s
package auth

import cats.data.{Kleisli, OptionT}
import cats.implicits._
import cats.{Applicative, Monad, MonadThrow}
import com.iheart.thomas.admin.{Role, User}
import org.http4s.dsl.Http4sDsl
import org.http4s.twirl._
import org.http4s.{HttpRoutes, Request, Response}
import tsec.authentication.{SecuredRequest, TSecAuthService, TSecMiddleware}
import tsec.authorization.{AuthGroup, AuthorizationInfo, BasicRBAC}

trait AuthedEndpointsUtils[F[_], Auth] {
  self: Http4sDsl[F] =>

  type AuthService = TSecAuthService[User, Token[Auth], F]

  type AuthEndpoint =
    PartialFunction[SecuredRequest[F, User, Token[Auth]], F[
      Response[F]
    ]]

  type Authenticator = tsec.authentication.Authenticator[
    F,
    Username,
    User,
    Token[Auth]
  ]

  implicit def authorizationInfoForUserRole(
      implicit F: Applicative[F]
    ): AuthorizationInfo[F, Role, User] =
    (u: User) => F.pure(u.role)

  def liftService(
      service: AuthService
    )(implicit authenticator: Authenticator,
      reverseRoutes: ReverseRoutes,
      F: Monad[F]
    ): HttpRoutes[F] = {
    val middleWare = TSecMiddleware(
      Kleisli(authenticator.extractAndValidate),
      (req: Request[F]) =>
        SeeOther(reverseRoutes.login(req.uri.renderString).location)
    )
    middleWare(service)
  }

  def roleBasedService(
      authGroup: AuthGroup[Role]
    )(pf: AuthEndpoint
    )(implicit
      F: MonadThrow[F],
      reverseRoutes: ReverseRoutes
    ): AuthService = {
    val auth = BasicRBAC.fromGroup[F, Role, User, Token[Auth]](authGroup)
    val onUnauthorized = BadRequest(
      html.errorMsg(
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
      roles: Seq[Role]
    )(pf: AuthEndpoint
    )(implicit
      F: MonadThrow[F],
      reverseRoutes: ReverseRoutes
    ): AuthService = roleBasedService(AuthGroup.fromSeq(roles))(pf)

}
