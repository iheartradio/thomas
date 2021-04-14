package lihua

import cats.Monad
import cats.tagless._

/**
  * Final tagless encoding of the DAO Algebra
  * @tparam F effect Monad
  * @tparam T type of the domain model
  */
@autoFunctorK
@autoContravariant
trait EntityDAO[F[_], T, Query] {
  def get(id: EntityId): F[Entity[T]]

  def insert(t: T): F[Entity[T]]

  def update(entity: Entity[T]): F[Entity[T]]

  def upsert(entity: Entity[T]): F[Entity[T]]

  def find(query: Query): F[Vector[Entity[T]]]

  def all: F[Vector[Entity[T]]]

  def findOne(query: Query): F[Entity[T]]

  def findOneOption(query: Query): F[Option[Entity[T]]]

  def remove(id: EntityId): F[Unit]

  def removeAll(query: Query): F[Int]

  /**
    * update the first entity query finds
    * @param query search query
    * @param entity to be updated to
    * @param upsert whether to insert of nothing is found
    * @return whether anything is updated
    */
  def update(
      query: Query,
      entity: Entity[T],
      upsert: Boolean
    ): F[Boolean]

  def upsert(
      query: Query,
      t: T
    ): F[Entity[T]]

  def removeAll(): F[Int]
}

object EntityDAO {
  /**
    * Provides more default implementation thanks to F being a Monad
    * @tparam F effect Monad
    * @tparam T type of the domain model
    * @tparam Query
    */
  abstract class EntityDAOMonad[F[_]: Monad, T, Query]
      extends EntityDAO[F, T, Query] {
    import cats.implicits._
    def upsert(
        query: Query,
        t: T
      ): F[Entity[T]] =
      findOneOption(query).flatMap(
        _.fold(insert(t))(e => update(e.copy(data = t)))
      )
  }
}
