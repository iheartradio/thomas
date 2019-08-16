package com.iheart.thomas
package bandit

import cats.Functor
import cats.implicits._
import lihua.{EntityDAO, EntityId}
import lihua.toDataOps

trait BanditStateDAO[F[_], BS] {
  def upsert(state: BS): F[BS]

  def get(featureName: FeatureName): F[BS]
}

object BanditStateDAO {
  def fromLihua[F[_]: Functor, BS <: BanditState[_]](
      dao: EntityDAO[F, BS, List[EntityId]]): BanditStateDAO[F, BS] =
    new BanditStateDAO[F, BS] {

      private def toEId(featureName: FeatureName): EntityId =
        EntityId(featureName + "_state")

      def upsert(state: BS): F[BS] =
        dao.upsert(state.toEntity(toEId(state.spec.feature))).map(_.data)

      def get(featureName: FeatureName): F[BS] =
        dao.get(toEId(featureName)).map(_.data)
    }
}
