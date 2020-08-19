package com.iheart.thomas.http4s

import java.time.OffsetDateTime

import cats.effect.{Async, Concurrent, Resource, Timer}
import cats.implicits._
import com.iheart.thomas
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.abtest.model.AbtestSpec
import com.iheart.thomas.http4s.AbtestService.{
  abtestAlgFromMongo,
  validationErrorMsg
}
import com.typesafe.config.ConfigFactory
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.twirl._
import org.http4s.HttpRoutes

import scala.concurrent.ExecutionContext
import FormDecoders._
import com.iheart.thomas.FeatureName
import com.iheart.thomas.abtest.Error.ValidationErrors
import com.iheart.thomas.http4s.AbtestAdminUI.{Filters, endAfter}
import org.http4s.FormDataDecoder.formEntityDecoder
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher

class AbtestAdminUI[F[_]: Async](alg: AbtestAlg[F]) extends Http4sDsl[F] {

  def routes = {

    def testsList(filters: Filters = Filters()) =
      alg
        .getAllTestsEndAfter(filters.endsAfter.getOrElse(OffsetDateTime.now))
        .flatMap { tests =>
          val toShow =
            filters.feature
              .fold(tests)(f => tests.filter(_.data.feature == f))
              .groupBy(_.data.feature)
              .mapValues(_.sortBy(_.data.start).toList)
              .toList
              .sortBy(_._1)
          Ok(abtest.admin.html.index(toShow, filters))
        }

    val adminRoutes =
      HttpRoutes
        .of[F] {
          case GET -> Root / "tests" :? endAfter(ea) =>
            testsList(Filters(ea))

          case GET -> Root / "new" =>
            Ok(abtest.admin.html.abtestForm(None))

          case req @ POST -> Root / "tests" =>
            req
              .as[AbtestSpec]
              .flatMap { spec =>
                alg
                  .create(spec, false)
                  .flatMap(_ => testsList())
                  .handleErrorWith { e =>
                    val errorMsg = e match {
                      case ValidationErrors(detail) =>
                        detail.toList.map(validationErrorMsg).mkString("<br/>")
                      case _ => e.getMessage
                    }
                    Ok(abtest.admin.html.abtestForm(Some(spec), Some(errorMsg)))
                  }
              }
              .handleErrorWith { e =>
                Ok(abtest.admin.html.errorMsg(e.getMessage))
              }
        }
    Router("/admin/" -> adminRoutes).orNotFound
  }

}

object AbtestAdminUI {

  case class Filters(
      endsAfter: Option[OffsetDateTime] = None,
      feature: Option[FeatureName] = None)

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

  object endAfter
      extends OptionalQueryParamDecoderMatcher[OffsetDateTime]("endAfter")
}
