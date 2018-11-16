/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package http.lib

import javax.inject._
import cats.data.EitherT
import cats.effect.IO
import com.iheart.thomas.analysis.KPIDistribution
import lihua.mongo._
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import com.iheart.thomas.http.lib.APIProvider.FailedToStartApplicationException
import lihua.EntityDAO
import play.api.libs.json.JsObject

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class APIProvider @Inject() (config: Configuration, lifecycle: ApplicationLifecycle)(implicit ex: ExecutionContext) {
  type F[T] = EitherT[IO, Error, T]

  implicit val shutdownHook = new ShutdownHook {
    override def onShutdown[T](code: => T): Unit = lifecycle.addStopHook(() => Future(code))
  }

  implicit val cfg = config.underlying
  import mongo.idSelector

  implicit val (api: API[F], kpiDAO: EntityDAO[F, KPIDistribution, JsObject]) = mongo.daos[IO].map { dao =>
    implicit val (abtestDAO, abtestExtraDAO, featureDAO, kpiDAO) = dao

      import scala.compat.java8.DurationConverters._
      val ttl = cfg.getDuration("iheart.abtest.get-groups.ttl").toScala //this is safe because it's in reference config
      (new DefaultAPI[F](ttl), kpiDAO)
  }.unsafeRunTimed(10.seconds)
    .getOrElse(throw new FailedToStartApplicationException("Cannot start application"))
}

object APIProvider {
  class FailedToStartApplicationException(msg: String) extends Exception(msg)
}
