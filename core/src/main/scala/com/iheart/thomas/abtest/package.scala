package com.iheart.thomas
package abtest

import cats.data.EitherT

object `package` {
  type APIResult[F[_], T] = EitherT[F, Error, T]

}
