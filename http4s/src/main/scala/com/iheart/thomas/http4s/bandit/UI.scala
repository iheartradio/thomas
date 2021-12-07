package com.iheart.thomas
package http4s
package bandit

import cats.effect.Async
import com.iheart.thomas.bandit.html._
import com.iheart.thomas.html._
import com.iheart.thomas.bandit.bayesian.BanditSpec
import com.iheart.thomas.http4s.AdminUI.AdminUIConfig
import com.iheart.thomas.http4s.auth.AuthedEndpointsUtils
import org.http4s.dsl.Http4sDsl
import tsec.authentication.asAuthed
import cats.implicits._
import com.iheart.thomas.abtest.model.{GroupMeta, GroupRange, GroupSize, UserMetaCriterion}
import com.iheart.thomas.analysis.{AllKPIRepo, KPIName}
import com.iheart.thomas.bandit.ArmSpec
import com.iheart.thomas.bandit.bayesian.BayesianMABAlg.BanditAbtestSpec
import com.iheart.thomas.http4s.bandit.UI.MismatchFeatureName
import org.http4s.FormDataDecoder
import org.http4s.FormDataDecoder._
import org.http4s.Uri.Path.Segment
import org.http4s.twirl._

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

class UI[F[_]: Async](
    implicit alg: ManagerAlg[F],
    kpiRepos: AllKPIRepo[F],
    aCfg: AdminUIConfig)
    extends AuthedEndpointsUtils[F, AuthImp]
    with Http4sDsl[F] {
  val reverseRoutes = ReverseRoutes(aCfg)
  import UI.decoders._

  val rootPath = Root / Segment("bandits")

  val kpis = kpiRepos.all.map(_.map(_.name))

  val routes = roleBasedService(admin.Authorization.banditsManagerRoles) {

    case GET -> `rootPath` / "" asAuthed (u) =>
      for {
        bandits <- alg.allBandits
        r <- Ok(index(bandits)(UIEnv(u)))
      } yield r

    case req @ POST -> `rootPath` / "" asAuthed (u) =>
      req.request
        .as[BanditSpec]
        .flatMap { bs =>
          alg.create(bs.copy(author = u.username)) *>
            Ok(redirect(reverseRoutes.bandit(bs.feature), "Bandit Created"))
        }

    case GET -> `rootPath` / "new" / "form" asAuthed (u) =>
      kpis.flatMap { ks =>
        Ok(newBandit(ks)(UIEnv(u)))
      }

    case GET -> `rootPath` / feature / "start" asAuthed (_) =>
      alg.start(feature) *>
        redirectTo(reverseRoutes.bandit(feature))

    case GET -> `rootPath` / feature / "" asAuthed (u) =>
      (kpis, alg.get(feature)).mapN { (ks, b) =>
        Ok(banditView(b, ks)(UIEnv(u)))
      }.flatten

    case req @ POST -> `rootPath` / feature / "" asAuthed u =>
      for {
        bs <- req.request.as[BanditSpec].ensure(MismatchFeatureName)(_.feature === feature)
        bas <- req.request.as[BanditAbtestSpec]
        r <-
          alg.update(bs.copy(author = u.username), bas) *>
            redirectTo(reverseRoutes.bandit(bs.feature))
        } yield r

    case GET -> `rootPath` / feature / "pause" asAuthed (_) =>
      alg.pause(feature) *>
        Ok(redirect(reverseRoutes.bandit(feature), "Bandit Paused"))

  }
}

object UI {
  case object MismatchFeatureName extends RuntimeException with NoStackTrace


  object decoders {
    import CommonFormDecoders._

    implicit val armSpecQueryDecoder: FormDataDecoder[ArmSpec] = (
      field[ArmName]("name"),
      fieldOptional[GroupSize]("size"),
      fieldOptional[GroupMeta]("meta"),
      fieldEither[Boolean]("reserved").default(false)
    ).mapN(ArmSpec.apply)

    implicit val bandSpecFDD: FormDataDecoder[BanditSpec] = (
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
      field[Int]("updatePolicyStateChunkSize"),
      field[FiniteDuration]("updatePolicyFrequency")
    ).mapN(BanditSpec.apply).sanitized

    import abtest.AbtestManagementUI.Decoders._

    implicit val banditAbtestSpecFDD: FormDataDecoder[BanditAbtestSpec] = (
      tags("requiredTags"),
      fieldOptional[UserMetaCriterion.And]("userMetaCriteria"),
      list[GroupRange]("segmentRanges")
    ).mapN(BanditAbtestSpec.apply).sanitized


    implicit val banditAndAbtestSpecFDD: FormDataDecoder[(BanditSpec,BanditAbtestSpec)] = (bandSpecFDD,banditAbtestSpecFDD).tupled


  }
}
