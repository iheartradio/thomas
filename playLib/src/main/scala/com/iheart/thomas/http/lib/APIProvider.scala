/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package http.lib

import javax.inject._
import cats.data.EitherT
import cats.effect.IO
import lihua.mongo._
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import com.iheart.thomas.http.lib.APIProvider.FailedToStartApplicationException

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class APIProvider @Inject() (config: Configuration, lifecycle: ApplicationLifecycle)(implicit ex: ExecutionContext) {
  type F[T] = EitherT[IO, Error, T]

  implicit val shutdownHook = new ShutdownHook {
    override def onShutdown[T](code: => T): Unit = lifecycle.addStopHook(() => Future(code))
  }

  implicit val cfg = config.underlying

  implicit val api: API[F] = mongo.daos[IO].map { implicit daos =>
    import scala.compat.java8.DurationConverters._
    val ttl = cfg.getDuration("iheart.abtest.get-groups.ttl").toScala //this is safe because it's in reference config
    new DefaultAPI[F](ttl)
  }.unsafeRunTimed(10.seconds)
    .getOrElse(throw new FailedToStartApplicationException("Cannot start application"))
}

object APIProvider {
  class FailedToStartApplicationException(msg: String) extends Exception(msg)
}
