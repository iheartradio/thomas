package com.iheart.thomas
package abtest

import cats.data.EitherT
import cats.{MonadError, ~>}
import cats.implicits._

object `package` {
  type APIResult[F[_], T] = EitherT[F, Error, T]

  def apiResultTo[F[_]: MonadError[?[_], Throwable]] =
    λ[APIResult[F, ?] ~> F](_.leftWiden[Throwable].rethrowT)

}
