package lihua
package mongo

import cats.tagless.FunctorK

trait Crypt[F[_]] {
  def encrypt(value: String): F[String]
  def decrypt(value: String): F[String]
}

object Crypt {
  implicit val functorKInstanceForCrypt: FunctorK[Crypt] = cats.tagless.Derive.functorK[Crypt]
}
