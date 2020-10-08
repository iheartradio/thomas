package com.iheart.thomas.http4s

import com.iheart.thomas.admin.User
import tsec.authentication.{AugmentedJWT, SecuredRequestHandler}

package object auth {

  type Token[A] = AugmentedJWT[A, String]

  type AuthedRequestHandler[F[_], Auth] = SecuredRequestHandler[
    F,
    String,
    User,
    Token[Auth]
  ]
}
