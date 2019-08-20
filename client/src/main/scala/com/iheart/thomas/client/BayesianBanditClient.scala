package com.iheart.thomas
package client

import bandit._
import bayesian._
import cats.effect.Sync
import com.iheart.thomas.analysis.{Conversions, KPIName}
import org.http4s.client.{Client => HClient}
import Formats._
import org.http4s.QueryParamEncoder

trait BayesianBanditClient[F[_], R] {
  def init(banditSpec: BanditSpec): F[BayesianMAB[R]]

  def currentState(featureName: FeatureName): F[BayesianMAB[R]]

  def reallocate(featureName: FeatureName, kpiName: KPIName): F[BayesianMAB[R]]

}

object BayesianBanditClient {

  implicit val te: QueryParamEncoder[KPIName] = KPIName.deriving

  def defaultConversion[F[_]: Sync](
      c: HClient[F],
      rootUrl: String): BayesianBanditClient[F, Conversions] =
    new PlayJsonHttp4sClient[F] with BayesianBanditClient[F, Conversions] {
      import org.http4s.{Method, Uri}
      import Method._

      def init(banditSpec: BanditSpec): F[BayesianMAB[Conversions]] =
        c.expect(POST(banditSpec, Uri.unsafeFromString(rootUrl + "/features/")))

      def currentState(featureName: FeatureName): F[BayesianMAB[Conversions]] =
        c.expect(rootUrl + "/features/" + featureName)

      def reallocate(featureName: FeatureName,
                     kpiName: KPIName): F[BayesianMAB[Conversions]] =
        c.expect(PUT(Uri.unsafeFromString(
          rootUrl + "/features/" + featureName + "/abtest") +? ("kpiName", kpiName)))

    }
}
