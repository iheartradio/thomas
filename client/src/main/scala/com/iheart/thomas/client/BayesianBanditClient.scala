package com.iheart.thomas
package client

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

import bandit._
import bayesian._
import cats.effect.{ConcurrentEffect, Sync}
import com.iheart.thomas.analysis.{Conversions, KPIName}
import org.http4s.client.{Client => HClient}
import Formats._
import cats.implicits._
import org.http4s.QueryParamEncoder

import scala.concurrent.ExecutionContext
import scala.util.control.NoStackTrace

object BayesianBanditClient {

  implicit val te: QueryParamEncoder[KPIName] =
    KPIName.deriving

  case object UnexpectedResult
      extends RuntimeException("unexpected return from service")
      with NoStackTrace

  def defaultConversion[F[_]: Sync](
      c: HClient[F],
      rootUrl: String
    ): ConversionBMABAlg[F] =
    new PlayJsonHttp4sClient[F](c)
    with BayesianMABAlg[F, Conversions, BanditSettings.Conversion] {

      import org.http4s.{Method, Uri}
      import Method._

      def init(banditSpec: ConversionBanditSpec): F[ConversionBandit] =
        c.expect(
          POST(
            banditSpec,
            Uri.unsafeFromString(rootUrl + "/features")
          )
        )

      def currentState(featureName: FeatureName): F[ConversionBandit] =
        c.expect(rootUrl + "/features/" + featureName)

      def updateRewardState(
          featureName: FeatureName,
          r: Map[ArmName, Conversions]
        ): F[BanditState[Conversions]] =
        c.expect(
          PUT(
            r,
            Uri.unsafeFromString(
              rootUrl + "/features/" + featureName + "/reward_state"
            )
          )
        )

      def update(
          settings: BanditSettings[BanditSettings.Conversion]
        ): F[BanditSettings[BanditSettings.Conversion]] =
        c.expect(
          PUT(
            settings,
            Uri.unsafeFromString(
              rootUrl + "/features/" + settings.feature + "/settings"
            )
          )
        )

      def updatePolicy(featureName: FeatureName): F[ConversionBandit] =
        c.expect(
          PUT(
            Uri.unsafeFromString(
              rootUrl + "/features/" + featureName + "/reallocate"
            )
          )
        )

      def getAll: F[Vector[ConversionBandit]] =
        c.expect(rootUrl + "/features")

      def runningBandits(asOf: Option[OffsetDateTime]): F[Vector[ConversionBandit]] =
        c.expect(
          Uri.unsafeFromString(
            rootUrl + "/features/running"
          ) +?? ("asOf", asOf.map(
            _.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
          ))
        )

      def delete(feature: FeatureName): F[Unit] =
        c.successful(
            DELETE(
              Uri.unsafeFromString(
                rootUrl + "/features/" + feature
              )
            )
          )
          .ensure(UnexpectedResult)(identity)
          .void

    }

  def defaultConversionResource[F[_]: ConcurrentEffect](
      rootUrl: String
    )(implicit ec: ExecutionContext
    ): cats.effect.Resource[F, ConversionBMABAlg[F]] = {
    import org.http4s.client.blaze.BlazeClientBuilder
    BlazeClientBuilder[F](ec).resource
      .map(cl => defaultConversion[F](cl, rootUrl))
  }

}
