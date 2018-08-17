/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas.http.lib

import javax.inject._
import cats.data.EitherT
import cats.effect.IO
import com.iheart.thomas.{API, DefaultAPI, Error}
import lihua.mongo._
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import cats.implicits._
import com.iheart.thomas.http.lib.APIProvider.FailedToStartApplicationException

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import play.api.Logger

@Singleton
class APIProvider @Inject() (config: Configuration, lifecycle: ApplicationLifecycle)(implicit ex: ExecutionContext) {
  type F[T] = EitherT[IO, Error, T]

  implicit val shutdownHook = new ShutdownHook {
    override def onShutdown[T](code: => T): Unit = lifecycle.addStopHook(() => Future(code))
  }

  implicit val cfg = config.underlying

  implicit val api: API[F] = DefaultAPI.instance.onError {
    case e => IO(Logger.error(e.toString))
  }.unsafeRunTimed(10.seconds)
    .getOrElse(throw new FailedToStartApplicationException("Cannot start application"))
}

object APIProvider {
  class FailedToStartApplicationException(msg: String) extends Exception(msg)
}
