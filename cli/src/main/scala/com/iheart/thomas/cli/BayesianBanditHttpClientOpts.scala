package com.iheart.thomas.cli

import cats.effect.ConcurrentEffect
import cats.implicits._
import com.iheart.thomas.client.BayesianBanditClient
import com.monovore.decline.Opts
import org.http4s.client.blaze.BlazeClientBuilder
import scala.concurrent.ExecutionContext

object BayesianBanditHttpClientOpts {

  val serviceRootPathOpts = (
    Opts.option[String]("host", "host of service"),
    Opts.option[String]("rootPath", "root path of service in play")
  ).mapN((h, r) => h + "/" + r)

  def conversionClientOpts[F[_]: ConcurrentEffect](implicit ec: ExecutionContext) = {
    serviceRootPathOpts.map { rootUrl =>
      BlazeClientBuilder[F](ec).resource
        .map(cl => BayesianBanditClient.defaultConversion[F](cl, rootUrl))
    }
  }
}
