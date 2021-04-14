package lihua

import io.estatico.newtype.macros.newtype

object `package` {
  @newtype
  case class EntityId(value: String)

  implicit def toDataOps[A](a: A): DataOps[A] = new DataOps(a)

  val idFieldName = "_id"  //determined by the field name of Entity
}

private[lihua] class DataOps[A](private val a: A) extends AnyVal {
  def toEntity(id: String): Entity[A] = toEntity(EntityId(id))
  def toEntity(entityId: EntityId): Entity[A] = Entity(entityId, a)
}
