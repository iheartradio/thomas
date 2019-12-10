/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart.thomas
package play
import java.time.Instant

import abtest._
import javax.inject._
import cats.effect.IO
import com.iheart.thomas.analysis.KPIApi
import lihua.mongo._
import _root_.play.api.Configuration
import _root_.play.api.inject.ApplicationLifecycle
import com.iheart.thomas.abtest.model.{Abtest, Feature}
import com.iheart.thomas.play.AbtestAPIProvider.FailedToStartApplicationException
import mau.RefreshRef

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AbtestAPIProvider @Inject()(
    config: Configuration,
    lifecycle: ApplicationLifecycle
  )(implicit ex: ExecutionContext)
    extends APIProviderBase(config, lifecycle)

class APIProviderBase(
    config: Configuration,
    lifecycle: ApplicationLifecycle
  )(implicit ex: ExecutionContext) {

  implicit val shutdownHook = new ShutdownHook {
    override def onShutdown[T](code: => T): Unit =
      lifecycle.addStopHook(() => Future(code))
  }

  implicit val cfg = config.underlying
  import mongo.idSelector

  lazy val daos = mongo
    .daos[IO]
    .unsafeRunTimed(10.seconds)
    .getOrElse(
      throw new FailedToStartApplicationException("Cannot start application")
    )

  lazy val refreshRate = {
    import scala.compat.java8.DurationConverters._
    //this is safe because it's in reference config
    cfg.getDuration("iheart.thomas.abtests.refresh.rate").toScala
  }
  implicit val cs = IO.contextShift(ex)
  implicit val timer = IO.timer(ex)

  implicit val refreshRef =
    RefreshRef
      .create[IO, (Vector[(lihua.Entity[Abtest], Feature)], Instant)](_ => IO.unit)
      .unsafeRunSync()

  lifecycle.addStopHook(() => refreshRef.cancel.unsafeToFuture())

  implicit val nowF = IO.delay(Instant.now)
  lazy val (api: AbtestAlg[IO], kpiApi: KPIApi[IO]) = {
    implicit val (abtestDAO, featureDAO, kpiDAO) = daos
    (new DefaultAbtestAlg[IO](refreshRate), KPIApi.default)
  }
}

object AbtestAPIProvider {
  class FailedToStartApplicationException(msg: String) extends Exception(msg)
}
