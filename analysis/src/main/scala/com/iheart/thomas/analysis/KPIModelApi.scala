package com.iheart
package thomas
package analysis

import lihua.{Entity, EntityDAO}
import _root_.play.api.libs.json.JsObject
import cats.implicits._
import abtest.Error
import cats.tagless.autoFunctorK
import com.iheart.thomas.abtest.Error.NotFound

import scala.reflect.ClassTag

@autoFunctorK //todo: To be retired, and replaced by ConversionKPIAlg
trait KPIModelApi[F[_]] {
  def get(name: KPIName): F[Option[Entity[KPIModel]]]

  def getAll: F[Vector[Entity[KPIModel]]]

  def getSpecific[K <: KPIModel](name: KPIName)(implicit classTag: ClassTag[K]): F[K]

  def upsert(kpi: KPIModel): F[Entity[KPIModel]]
}

object KPIModelApi {

  implicit def default[F[_]](
      implicit dao: EntityDAO[F, KPIModel, JsObject],
      F: MonadThrowable[F]
    ): KPIModelApi[F] =
    new KPIModelApi[F] {
      import com.iheart.thomas.abtest.QueryDSL._
      def get(name: KPIName): F[Option[Entity[KPIModel]]] =
        dao.findOneOption('name -> name)

      def getAll: F[Vector[Entity[KPIModel]]] =
        dao.all

      def getSpecific[K <: KPIModel](
          name: KPIName
        )(implicit classTag: ClassTag[K]
        ): F[K] =
        get(name).flatMap {
          case Some(Entity(id, k: K)) => F.pure(k)
          case _ =>
            F.raiseError(
              NotFound(s"Cannot find the KPI of name $name and ${classTag}")
            )
        }

      def upsert(kpi: KPIModel): F[Entity[KPIModel]] =
        dao
          .findOne('name -> kpi.name.n)
          .flatMap(e => dao.update(e.copy(data = kpi)))
          .recoverWith { case Error.NotFound(_) => dao.insert(kpi) }
    }
}
