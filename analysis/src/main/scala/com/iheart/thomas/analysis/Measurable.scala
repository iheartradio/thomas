package com.iheart.thomas
package analysis

import java.time.OffsetDateTime

import cats.Contravariant
import model.{Abtest, GroupName}

trait Measurable[F[_], M, K] {
  def measureAbtest(k: K,
                    abtest: Abtest,
                    start: Option[OffsetDateTime],
                    end: Option[OffsetDateTime]): F[Map[GroupName, M]]
  def measureHistory(k: K, start: OffsetDateTime, end: OffsetDateTime): F[M]
}

object Measurable{
  implicit def contravariantInst[F[_], M]: Contravariant[Measurable[F, M, ?]] = cats.tagless.Derive.contravariant[Measurable[F, M, ?]]
}
