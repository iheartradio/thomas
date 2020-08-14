package com.iheart.thomas.http4s

import cats.effect.{Async, Concurrent, Resource, Timer}
import cats.implicits._
import com.iheart.thomas
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.abtest.model.AbtestSpec
import com.iheart.thomas.http4s.AbtestService.abtestAlgFromMongo
import com.typesafe.config.ConfigFactory
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.twirl._
import org.http4s.HttpRoutes

import scala.concurrent.ExecutionContext
import FormDecoders._
import org.http4s.FormDataDecoder.formEntityDecoder

class AbtestAdminUI[F[_]: Async](alg: AbtestAlg[F]) extends Http4sDsl[F] {

  def routes = {
    val adminRoutes =
      HttpRoutes
        .of[F] {
          case GET -> Root =>
            alg.getAllTests(None).flatMap { tests =>
              Ok(abtest.admin.html.index(tests))
            }
          case GET -> Root / "new" =>
            Ok(abtest.admin.html.abtestForm(None))

          case req @ POST -> Root / "tests" =>
            req.as[AbtestSpec].flatMap { spec =>
              Ok(abtest.admin.html.abtestForm(Some(spec)))
            }

        }

    Router("/admin/" -> adminRoutes).orNotFound
  }

}

object AbtestAdminUI {
  def fromMongo[F[_]: Timer](
      implicit F: Concurrent[F],
      ex: ExecutionContext
    ): Resource[F, AbtestAdminUI[F]] = {

    for {
      cfg <- Resource.liftF(F.delay(ConfigFactory.load))
      daos <- thomas.mongo.daosResource[F](cfg)
      alg <- {
        implicit val (c, d) = (cfg, daos)
        abtestAlgFromMongo[F]
      }
    } yield {
      new AbtestAdminUI[F](alg)
    }
  }
}
