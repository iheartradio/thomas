package com.iheart.thomas
package http4s

import com.iheart.thomas.admin.{Role, User}
import tsec.authentication.{AugmentedJWT, SecuredRequestHandler}
import tsec.authorization.AuthGroup

package object auth {

  type Token[A] = AugmentedJWT[A, Username]

  type AuthedRequestHandler[F[_], Auth] = SecuredRequestHandler[
    F,
    Username,
    User,
    Token[Auth]
  ]

  object Permissions {
    import Roles._

    val readableRoles: AuthGroup[Role] =
      AuthGroup.fromSeq(values.filter(_ != Guest))

    val testManagerRoles: AuthGroup[Role] =
      AuthGroup(Admin, Tester, Developer)
  }
}
