package com.iheart.thomas.abtest

import cats.Functor
import com.iheart.thomas.{FeatureName, utils}
import com.iheart.thomas.abtest.model.Feature
import lihua.{Entity, EntityDAO}
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json.{JsArray, JsObject, JsString, Json}
import monocle.macros.syntax.lens._
import cats.syntax.all._
import java.time.Instant
import scala.concurrent.duration.FiniteDuration

trait TestsDataProvider[F[_]] {
  def getTestsData(
      at: Instant,
      duration: Option[FiniteDuration]
    ): F[TestsData]
}
trait FeatureRetriever[F[_]] {
  def getFeature(name: FeatureName): F[Feature]
}

trait FeatureRepo[F[_]] extends FeatureRetriever[F] {
  def findByNames(names: Seq[FeatureName]): F[Seq[Entity[Feature]]]
  def insert(f: Feature): F[Entity[Feature]]
  def byNameOption(name: FeatureName): F[Option[Entity[Feature]]]
  def byName(name: FeatureName): F[Entity[Feature]]

  def all: F[Vector[Entity[Feature]]]

  def update(f: Entity[Feature]): F[Entity[Feature]]
  def obtainLock(
      feature: Entity[Feature],
      at: Instant,
      gracePeriod: Option[FiniteDuration]
    ): F[Boolean]

  def releaseLock(feature: Entity[Feature]): F[Entity[Feature]] =
    update(feature.lens(_.data.lockedAt).set(None))
}

object FeatureRepo {
  import com.iheart.thomas.abtest.QueryDSL._

  implicit def fromEntityDAO[F[_]: Functor](
      implicit dao: EntityDAO[F, Feature, JsObject]
    ): FeatureRepo[F] = new FeatureRepo[F] {
    def findByNames(names: Seq[FeatureName]): F[Seq[Entity[Feature]]] = dao
      .find(
        Json.obj(
          "name" ->
            Json.obj(
              "$in" ->
                JsArray(
                  names.map(JsString(_))
                )
            )
        )
      )
      .widen

    def byName(name: FeatureName): F[Entity[Feature]] =
      dao.byName(name)

    def getFeature(name: FeatureName): F[Feature] = byName(name).map(_.data)

    def byNameOption(name: FeatureName): F[Option[Entity[Feature]]] =
      dao.byNameOption(name)

    def insert(f: Feature): F[Entity[Feature]] =
      dao.insert(f)

    def all: F[Vector[Entity[Feature]]] =
      dao.all

    def update(f: Entity[Feature]): F[Entity[Feature]] = dao.update(f)

    def obtainLock(
        feature: Entity[Feature],
        at: Instant,
        gracePeriod: Option[FiniteDuration]
      ): F[Boolean] = {
      import utils.time.InstantOps
      val noLock: (String, JsValueWrapper) =
        "lockedAt" -> Json.obj("$exists" -> false)

      val lockCheck: (String, JsValueWrapper) =
        gracePeriod.fold(
          noLock
        ) { gp =>
          "$or" -> Json.arr(
            Json.obj(noLock),
            Json.obj(
              "lockedAt" ->
                Json.obj("$lt" -> at.plusDuration(gp.inverse()))
            )
          )
        }

      dao
        .update(
          Json.obj(
            "name" -> feature.data.name,
            lockCheck
          ),
          feature.lens(_.data.lockedAt).set(Some(at)),
          upsert = false
        )
    }

  }

}
