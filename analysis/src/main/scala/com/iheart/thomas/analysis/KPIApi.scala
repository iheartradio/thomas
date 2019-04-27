package com.iheart
package thomas
package analysis

import cats.MonadError
import lihua.{Entity, EntityDAO}
import _root_.play.api.libs.json.JsObject
import cats.implicits._

trait KPIApi[F[_]] {
  def get(name: KPIName): F[Option[Entity[KPIDistribution]]]
  def upsert(kpi: KPIDistribution): F[Entity[KPIDistribution]]
}


object KPIApi {
  def default[F[_]](implicit dao: EntityDAO[F, KPIDistribution, JsObject],
                    F: MonadError[F, Error]): KPIApi[F] = new KPIApi[F] {
    import QueryDSL._
    def get(name: KPIName): F[Option[Entity[KPIDistribution]]] =
      dao.findOneOption('name -> name)

    def upsert(kpi: KPIDistribution): F[Entity[KPIDistribution]] =
      dao.findOne('name -> kpi.name.n)
        .flatMap(e => dao.update(e.copy(data = kpi)))
        .recoverWith { case Error.NotFound(_) => dao.insert(kpi)}
  }
}
