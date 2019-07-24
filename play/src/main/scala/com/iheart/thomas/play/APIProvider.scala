/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package play
import abtest._

import javax.inject._
import cats.data.EitherT
import cats.effect.IO
import com.iheart.thomas.analysis.KPIApi
import lihua.mongo._
import _root_.play.api.Configuration
import _root_.play.api.inject.ApplicationLifecycle
import com.iheart.thomas.play.APIProvider.FailedToStartApplicationException

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scalacache.CatsEffect.modes._
@Singleton
class APIProvider @Inject() (config: Configuration, lifecycle: ApplicationLifecycle)(implicit ex: ExecutionContext) extends APIProviderBase(config, lifecycle)

class APIProviderBase(config: Configuration, lifecycle: ApplicationLifecycle)(implicit ex: ExecutionContext) {
  type F[T] = EitherT[IO, Error, T]

  implicit val shutdownHook = new ShutdownHook {
    override def onShutdown[T](code: => T): Unit = lifecycle.addStopHook(() => Future(code))
  }

  implicit val cfg = config.underlying
  import mongo.idSelector

  lazy val daos = mongo.daos[IO].unsafeRunTimed(10.seconds).
    getOrElse(throw new FailedToStartApplicationException("Cannot start application"))

  lazy val ttl = {
    import scala.compat.java8.DurationConverters._
    //this is safe because it's in reference config
    cfg.getDuration("iheart.abtest.get-groups.ttl").toScala
  }

  lazy val (api: AbtestAlg[F], kpiApi: KPIApi[F]) = {
    implicit val (abtestDAO, featureDAO, kpiDAO) = daos
    (new DefaultAbtestAlg[F](ttl), KPIApi.default)
  }
}

object APIProvider {
  class FailedToStartApplicationException(msg: String) extends Exception(msg)
}
