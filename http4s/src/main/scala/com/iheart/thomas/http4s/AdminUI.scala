package com.iheart.thomas.http4s

import com.iheart.thomas.http4s.abtest.AbtestManagementUI
import com.iheart.thomas.http4s.auth.{
  AuthDependencies,
  AuthedEndpointsUtils,
  AuthedRequestHandler
}
import org.http4s.dsl.Http4sDsl
import cats.implicits._
import com.iheart.thomas.{MonadThrowable, dynamo}
import cats.effect._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.admin.Role
import com.typesafe.config.Config
import org.http4s.server.{Router, Server}
import org.http4s.server.blaze.BlazeServerBuilder
import pureconfig.ConfigSource
import pureconfig.module.catseffect._
import tsec.common.SecureRandomIdGenerator

import scala.concurrent.ExecutionContext

class AdminUI[F[_]: MonadThrowable](
    abtestManagementUI: AbtestManagementUI[F],
    authUI: auth.UI[F, AuthImp]
  )(implicit reverseRoutes: ReverseRoutes,
    requestHandler: AuthedRequestHandler[F, AuthImp])
    extends AuthedEndpointsUtils[F, AuthImp]
    with Http4sDsl[F] {

  val routes = authUI.publicEndpoints <+> liftService(
    abtestManagementUI.routes <+> authUI.authedService
  )
}

object AdminUI {
  def generateKey: String =
    SecureRandomIdGenerator(256).generate

  case class AdminUIConfig(
      key: String,
      rootPath: String,
      authTableReadCapacity: Long,
      authTableWriteCapacity: Long,
      initialAdminUsername: String,
      initialRole: Role)

  def loadConfig[F[_]: Sync](cfg: Config): F[AdminUIConfig] = {
    import pureconfig.generic.auto._
    ConfigSource.fromConfig(cfg).at("thomas.admin-ui").loadF[F, AdminUIConfig]
  }

  def resource[F[_]: Concurrent: Timer](
      cfg: AdminUIConfig,
      mongoAbtest: Config
    )(implicit dc: AmazonDynamoDBAsync,
      ec: ExecutionContext
    ): Resource[F, AdminUI[F]] = {

    implicit val rr = new ReverseRoutes(cfg.rootPath)
    Resource.liftF(
      dynamo.AdminDAOs.ensureAuthTables[F](
        cfg.authTableReadCapacity,
        cfg.authTableWriteCapacity
      )
    ) *> {
      Resource.liftF(AuthDependencies[F](cfg.key)).flatMap { deps =>
        import deps._
        import dynamo.AdminDAOs._
        val authUI =
          auth.UI.default[F](deps, Some(cfg.initialAdminUsername), cfg.initialRole)

        AbtestManagementUI.fromMongo[F](mongoAbtest).map { amUI =>
          new AdminUI(amUI, authUI)
        }
      }
    }
  }

  /**
    * Provides a server that serves the Admin UI
    */
  def serverResource[F[_]: ConcurrentEffect: Timer](
      implicit dc: AmazonDynamoDBAsync,
      executionContext: ExecutionContext
    ): Resource[F, Server[F]] = {
    import org.http4s.server.blaze._
    import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT

    for {
      cfg <- ConfigResource.cfg[F]()
      adminCfg <- Resource.liftF(AdminUI.loadConfig[F](cfg))
      ui <- AdminUI.resource[F](adminCfg, cfg)
      server <- BlazeServerBuilder[F](executionContext)
        .bindHttp(8080, "0.0.0.0")
        .withHttpApp(
          Router(adminCfg.rootPath -> ui.routes).orNotFound
        )
        .resource

    } yield server
  }
}
