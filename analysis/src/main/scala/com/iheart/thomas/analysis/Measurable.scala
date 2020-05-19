package com.iheart.thomas
package analysis

import java.time.Instant

import cats.Contravariant
import com.iheart.thomas.abtest.model.Abtest

trait Measurable[F[_], M, K] {
  def measureAbtest(
      k: K,
      abtest: Abtest,
      start: Option[Instant],
      end: Option[Instant]
    ): F[Map[GroupName, M]]

  def measureHistory(
      k: K,
      start: Instant,
      end: Instant
    ): F[M]
}

object Measurable {
  implicit def contravariantInst[F[_], M]: Contravariant[Measurable[F, M, *]] =
    cats.tagless.Derive.contravariant[Measurable[F, M, *]]
}
