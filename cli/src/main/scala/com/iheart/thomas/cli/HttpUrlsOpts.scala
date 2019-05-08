package com.iheart.thomas
package cli

import cats.effect.ConcurrentEffect
import com.monovore.decline.Opts
import cats.implicits._
import com.iheart.thomas.client.Client.HttpServiceUrlsPlay
import com.iheart.thomas.client.Http4sClient

object HttpClientOpts {

  val serviceUrlsOpts = (
    Opts.option[String]("host", "host of service"),
    Opts.option[String]("rootPath", "root path of service in play")
  ).mapN((h, r) => new HttpServiceUrlsPlay(h + "/" + r ))


  def opts[F[_]: ConcurrentEffect] = {
    serviceUrlsOpts.map { urls =>
      Http4sClient.resource(urls, concurrent.ExecutionContext.Implicits.global)
    }
  }
}


