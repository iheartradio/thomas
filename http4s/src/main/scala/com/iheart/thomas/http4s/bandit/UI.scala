package com.iheart.thomas
package http4s
package bandit

import cats.effect.Async
import com.iheart.thomas.bandit.html._
import com.iheart.thomas.bandit.bayesian.{BanditSpec, BayesianMABAlg}
import com.iheart.thomas.http4s.AdminUI.AdminUIConfig
import com.iheart.thomas.http4s.auth.AuthedEndpointsUtils
import org.http4s.dsl.Http4sDsl
import tsec.authentication.asAuthed
import cats.implicits._
import com.iheart.thomas.abtest.model.{GroupMeta, GroupSize}
import com.iheart.thomas.analysis.{AllKPIRepo, KPIName}
import com.iheart.thomas.bandit.ArmSpec
import org.http4s.FormDataDecoder
import org.http4s.twirl._

import scala.concurrent.duration.FiniteDuration

class UI[F[_]: Async](
    implicit alg: BayesianMABAlg[F],
    kpiRepos: AllKPIRepo[F],
    aCfg: AdminUIConfig)
    extends AuthedEndpointsUtils[F, AuthImp]
    with Http4sDsl[F] {
//  val reverseRoutes = ReverseRoutes(aCfg)

  val rootPath = Root / "bandits"

  val routes = roleBasedService(admin.Authorization.banditsManagerRoles) {

    case GET -> `rootPath` / "" asAuthed (u) =>
      for {
        bandits <- alg.getAll
        r <- Ok(index(bandits)(UIEnv(u)))
      } yield r

    case GET -> `rootPath` / "new" / "form" asAuthed (u) =>
      kpiRepos.all.flatMap { kpis =>
        Ok(newBandit(kpis.map(_.name))(UIEnv(u)))
      }

  }
}

object UI {
  object decoders {
    import CommonFormDecoders._
    import org.http4s.FormDataDecoder._
    implicit val armSpecQueryDecoder: FormDataDecoder[ArmSpec] = (
      field[ArmName]("name"),
      fieldOptional[GroupSize]("size"),
      fieldOptional[GroupMeta]("meta"),
      fieldEither[Boolean]("reserved").default(false)
    ).mapN(ArmSpec.apply)

    implicit val bandSpec: FormDataDecoder[BanditSpec] = (
      field[FeatureName]("feature"),
      field[String]("title"),
      field[String]("author"),
      field[KPIName]("kpiName"),
      list[ArmSpec]("groups"),
      field[Double]("minimumSizeChange"),
      fieldOptional[FiniteDuration]("historyRetention"),
      field[Int]("initialSampleSize"),
      field[Int]("stateMonitorEventChunkSize"),
      field[FiniteDuration]("stateMonitorFrequency"),
      field[Int]("updatePolicyEveryNStateUpdate"),
      field[FiniteDuration]("updatePolicyFrequency")
    ).mapN(BanditSpec.apply).sanitized

  }
}
