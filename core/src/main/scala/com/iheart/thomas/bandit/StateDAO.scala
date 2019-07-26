package com.iheart.thomas.bandit

import cats.Functor
import cats.implicits._
import com.iheart.thomas.FeatureName
import com.iheart.thomas.bandit.model.BanditState
import lihua.{EntityDAO, EntityId}
import lihua.toDataOps

trait StateDAO[F[_], R] {
  def upsert(state: BanditState[R]): F[BanditState[R]]

  def get(featureName: FeatureName): F[BanditState[R]]
}

object StateDAO {
  implicit def fromLihua[F[_]: Functor, R](
      implicit dao: EntityDAO[F, BanditState[R], List[EntityId]]): StateDAO[F, R] =
    new StateDAO[F, R] {

      private def toEId(featureName: FeatureName): EntityId =
        EntityId(featureName + "_state")

      def upsert(state: BanditState[R]): F[BanditState[R]] =
        dao.upsert(state.toEntity(toEId(state.spec.feature))).map(_.data)

      def get(featureName: FeatureName): F[BanditState[R]] =
        dao.get(toEId(featureName)).map(_.data)
    }
}
