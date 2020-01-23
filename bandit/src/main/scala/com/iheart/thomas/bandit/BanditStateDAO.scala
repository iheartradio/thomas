package com.iheart.thomas
package bandit

import cats.Functor
import cats.implicits._
import lihua.{Entity, EntityDAO, EntityId, toDataOps}

trait BanditStateDAO[F[_], BS] {
  def upsert(state: BS): F[BS]
  def update(state: BS): F[BS]
  def remove(featureName: FeatureName): F[Unit]

  def get(featureName: FeatureName): F[BS]
}

object BanditStateDAO {
  def bayesianfromLihua[F[_]: Functor, R](
      dao: EntityDAO[F, bayesian.BanditState[R], List[
        EntityId
      ]]
    ): BanditStateDAO[F, bayesian.BanditState[R]] =
    new BanditStateDAO[F, bayesian.BanditState[R]] {

      private def toEId(featureName: FeatureName): EntityId =
        EntityId(featureName + "_state")

      private def toE(
          state: bayesian.BanditState[R]
        ): Entity[bayesian.BanditState[R]] =
        state.toEntity(toEId(state.feature))

      def upsert(state: bayesian.BanditState[R]): F[bayesian.BanditState[R]] =
        dao
          .upsert(toE(state))
          .map(_.data)

      def update(state: bayesian.BanditState[R]): F[bayesian.BanditState[R]] =
        dao
          .update(toE(state))
          .map(_.data)

      def get(featureName: FeatureName): F[bayesian.BanditState[R]] =
        dao.get(toEId(featureName)).map(_.data)

      def remove(featureName: FeatureName): F[Unit] =
        dao.remove(toEId(featureName))

    }
}
