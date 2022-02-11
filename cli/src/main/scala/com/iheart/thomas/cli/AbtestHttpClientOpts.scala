package com.iheart.thomas
package cli

import cats.effect.Async
import com.monovore.decline.Opts
import cats.syntax.all._
import com.iheart.thomas.client.AbtestClient.HttpServiceUrlsPlay
import com.iheart.thomas.client.Http4SAbtestClient

object AbtestHttpClientOpts {

  val serviceUrlsOpts = (
    Opts.option[String]("host", "host of service"),
    Opts.option[String]("rootPath", "root path of service in play")
  ).mapN((h, r) => new HttpServiceUrlsPlay(h + "/" + r))

  def opts[F[_]: Async] = {
    serviceUrlsOpts.map { urls =>
      Http4SAbtestClient.resource(urls, concurrent.ExecutionContext.Implicits.global)
    }
  }
}
