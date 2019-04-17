/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas

import cats.{Applicative, Id, Monad}
import com.iheart.thomas.model.{Abtest, UserGroupQuery}
import cats.implicits._
import cats.kernel.Semigroup

import scala.util.matching.Regex

trait EligibilityControl[F[_]] {

  def eligible(
    userInfo: UserGroupQuery,
    test:     Abtest
  ): F[Boolean]

}

object EligibilityControl extends EligibilityControlInstances0 {
  def apply[F[_]](f: (UserGroupQuery, Abtest) => F[Boolean]): EligibilityControl[F] = new EligibilityControl[F] {
    def eligible(userInfo: UserGroupQuery, test: Abtest): F[Boolean] =
      f(userInfo, test)
  }

  implicit def semigroupAnd[F[_]: Monad]: Semigroup[EligibilityControl[F]] = new Semigroup[EligibilityControl[F]] {
    override def combine(x: EligibilityControl[F], y: EligibilityControl[F]): EligibilityControl[F] =
      apply[F]((userInfo: UserGroupQuery, test: Abtest) => x.eligible(userInfo, test).flatMap { a =>
        if (a) {
          y.eligible(userInfo, test)
        } else false.pure[F]
      })
  }
}

abstract class EligibilityControlInstances0 extends EligibilityControlInstances1 {

  implicit def default: EligibilityControl[Id] =
    byGroupMeta |+| byRequiredTags |+| bySegRanges

  lazy val byGroupMeta: EligibilityControl[Id] = EligibilityControl[Id]((userInfo, test) =>
    test.matchingUserMeta.forall {
      case (k, r) => userInfo.meta.get(k).fold(false)(v => new Regex(r).findFirstMatchIn(v).isDefined)
    })

  lazy val byRequiredTags: EligibilityControl[Id] = EligibilityControl[Id]((userInfo: UserGroupQuery, test: Abtest) =>
    test.requiredTags.forall(userInfo.tags.contains))

  lazy val bySegRanges: EligibilityControl[Id] = EligibilityControl[Id]((userInfo: UserGroupQuery, test: Abtest) =>
    if (test.segmentRanges.isEmpty) true
    else test.segmentRanges.exists { range =>
      test.idToUse(userInfo).fold(false) { id =>
        range.contains(Bucketing.md5Double(id))
      }
    })

}

sealed abstract class EligibilityControlInstances1 {

  implicit def fromIdEControl[F[_]: Applicative](implicit idec: EligibilityControl[Id]): EligibilityControl[F] = EligibilityControl[F](
    (u, t) => idec.eligible(u, t).pure[F]
  )

}
