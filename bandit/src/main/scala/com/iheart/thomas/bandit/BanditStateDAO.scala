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
  def bayesianfromLihua[F[_]: Functor, R](
      dao: EntityDAO[F, bayesian.BanditState[R], List[
        EntityId
      ]]
    ): BanditStateDAO[F, bayesian.BanditState[R]] =
    new BanditStateDAO[F, bayesian.BanditState[R]] {

      private def toEId(
          featureName: FeatureName
        ): EntityId =
        EntityId(featureName + "_state")

      def upsert(
          state: bayesian.BanditState[R]
        ): F[bayesian.BanditState[R]] =
        dao
          .upsert(state.toEntity(toEId(state.feature)))
          .map(_.data)

      def get(
          featureName: FeatureName
        ): F[bayesian.BanditState[R]] =
        dao.get(toEId(featureName)).map(_.data)

    }
}
