package com.iheart.thomas
package analysis

import java.time.OffsetDateTime

import cats.tagless.autoContravariant
import model.{Abtest, GroupName}

@autoContravariant
trait Measurable[F[_], M, K] {
  def measureAbtest(k: K, abtest: Abtest): F[Map[GroupName, M]]
  def measureHistory(k: K, start: OffsetDateTime, end: OffsetDateTime): F[M]
}
