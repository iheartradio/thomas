package com.iheart.thomas
package http4s

import com.iheart.thomas.admin.{Role, User}
import tsec.authentication.{AugmentedJWT, SecuredRequestHandler}
import tsec.authorization.{AuthGroup, SimpleAuthEnum}
import cats.implicits._

package object auth {

  type Token[A] = AugmentedJWT[A, Username]

  type AuthedRequestHandler[F[_], Auth] = SecuredRequestHandler[
    F,
    Username,
    User,
    Token[Auth]
  ]

  implicit object Roles extends SimpleAuthEnum[Role, String] {
    val values: AuthGroup[Role] =
      AuthGroup.fromSeq(Role.values)

    def getRepr(t: Role): String = t.name
  }
}
