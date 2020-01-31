/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package abtest

import java.time.OffsetDateTime

import cats.implicits._
import cats.kernel.Semigroup
import cats.{Applicative, Id, Monad}
import com.iheart.thomas.abtest
import com.iheart.thomas.abtest.model.Abtest.EligibilityType
import com.iheart.thomas.abtest.model.Abtest.Status.InProgress
import model._

import scala.util.matching.Regex
import henkan.convert.Syntax._

trait EligibilityControl[F[_]] {

  def eligible(
      query: UserGroupQuery,
      test: Abtest
    ): F[Boolean]

  def and(that: EligibilityControl[F])(implicit F: Monad[F]): EligibilityControl[F] =
    EligibilityControl[F](
      (userInfo: UserGroupQuery, test: Abtest) =>
        eligible(userInfo, test).flatMap { a =>
          if (a) {
            that.eligible(userInfo, test)
          } else false.pure[F]
        }
    )

  def or(that: EligibilityControl[F])(implicit F: Monad[F]): EligibilityControl[F] =
    EligibilityControl[F](
      (userInfo: UserGroupQuery, test: Abtest) =>
        eligible(userInfo, test).flatMap { a =>
          if (!a) {
            that.eligible(userInfo, test)
          } else true.pure[F]
        }
    )
}

object EligibilityControl extends EligibilityControlInstances0 {
  def apply[F[_]](f: (UserGroupQuery, Abtest) => F[Boolean]): EligibilityControl[F] =
    new EligibilityControl[F] {
      def eligible(
          userInfo: UserGroupQuery,
          test: Abtest
        ): F[Boolean] =
        f(userInfo, test)
    }

  implicit def semigroupAnd[F[_]: Monad]: Semigroup[EligibilityControl[F]] =
    new Semigroup[EligibilityControl[F]] {
      override def combine(
          x: EligibilityControl[F],
          y: EligibilityControl[F]
        ): EligibilityControl[F] = x and y
    }
}

private[thomas] sealed abstract class EligibilityControlInstances0
    extends EligibilityControlInstances1 {

  implicit def default[F[_]: Applicative]: EligibilityControl[F] =
    new EligibilityControl[F] {
      def eligible(
          query: UserGroupQuery,
          test: Abtest
        ): F[Boolean] =
        (byUserEligibility and bySegRanges and byTestEffectiveRange)
          .eligible(query, test)
          .pure[F]

    }

  lazy val byUserEligibility: EligibilityControl[Id] =
    byTestEligibilityType or (byGroupMeta and byRequiredTags)

  lazy val byTestEligibilityType: EligibilityControl[Id] =
    abtest.EligibilityControl[Id](
      (_, test) => test.eligibilityType == EligibilityType.AllEligible
    )

  lazy val byGroupMeta: EligibilityControl[Id] =
    abtest.EligibilityControl[Id](
      (query, test) =>
        test.matchingUserMeta.forall {
          case (k, r) =>
            query.meta
              .get(k)
              .fold(false)(v => new Regex(r).findFirstMatchIn(v).isDefined)
        }
    )

  lazy val byRequiredTags: EligibilityControl[Id] =
    abtest.EligibilityControl[Id](
      (query: UserGroupQuery, test: Abtest) =>
        test.requiredTags.forall(query.tags.contains)
    )

  lazy val byTestEffectiveRange: EligibilityControl[Id] =
    abtest.EligibilityControl[Id](
      (query: UserGroupQuery, test: Abtest) =>
        test.statusAsOf(query.at.getOrElse(OffsetDateTime.now)) === InProgress
    )

  lazy val bySegRanges: EligibilityControl[Id] = abtest.EligibilityControl[Id](
    (query: UserGroupQuery, test: Abtest) =>
      if (test.segmentRanges.isEmpty) true
      else
        test.segmentRanges.exists { range =>
          test.idToUse(query.to[UserInfo]()).fold(false) { id =>
            range.contains(Bucketing.md5Double(id))
          }
        }
  )

}

private[thomas] sealed abstract class EligibilityControlInstances1 {

  implicit def fromIdEControl[F[_]: Applicative](
      implicit idec: EligibilityControl[Id]
    ): EligibilityControl[F] =
    EligibilityControl[F](
      (u, t) => idec.eligible(u, t).pure[F]
    )

}
