package com.iheart

import cats.data.EitherT

package object thomas {
  type APIResult[F[_], T] = EitherT[F, Error, T]

}
