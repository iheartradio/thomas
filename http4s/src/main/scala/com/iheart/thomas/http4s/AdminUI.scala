package com.iheart.thomas
package http4s

import com.iheart.thomas.http4s.abtest.AbtestManagementUI
import com.iheart.thomas.http4s.auth.{
  AuthDependencies,
  AuthedEndpointsUtils,
  AuthenticationAlg,
  Token,
  UI
}
import org.http4s.dsl.Http4sDsl
import cats.implicits._
import com.iheart.thomas.dynamo
import cats.effect._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import com.iheart.thomas.admin.{Role, User}
import com.typesafe.config.Config
import org.http4s.Response
import org.http4s.server.{Router, ServiceErrorHandler}
import org.http4s.server.blaze.BlazeServerBuilder
import pureconfig.error.{CannotConvert, FailureReason}
import pureconfig.{ConfigReader, ConfigSource}
import pureconfig.module.catseffect._
import cats.MonadThrow
import com.iheart.thomas.http4s.AdminUI.AdminUIConfig
import com.iheart.thomas.kafka.JsonMessageSubscriber
import com.iheart.thomas.stream.{ArmParser, JobAlg}
import io.chrisdavenport.log4cats.Logger

import scala.concurrent.ExecutionContext
import org.http4s.twirl._
import tsec.authentication.Authenticator
import tsec.passwordhashers.jca.BCrypt
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.typelevel.jawn.ast.JValue
import ThrowableExtension._

class AdminUI[F[_]: MonadThrow](
    abtestManagementUI: AbtestManagementUI[F],
    authUI: auth.UI[F, AuthImp],
    analysisUI: analysis.UI[F],
    streamUI: stream.UI[F]
  )(implicit adminUICfg: AdminUIConfig,
    jobAlg: JobAlg[F],
    authenticator: Authenticator[F, String, User, Token[AuthImp]])
    extends AuthedEndpointsUtils[F, AuthImp]
    with Http4sDsl[F] {

  val routes = authUI.publicEndpoints <+> liftService(
    abtestManagementUI.routes <+> authUI.authedService <+> analysisUI.routes <+> streamUI.routes
  )

  val serverErrorHandler: ServiceErrorHandler[F] = { _ =>
    {
      case admin.Authorization.LackPermission =>
        Response[F](Unauthorized).pure[F]
      case e =>
        InternalServerError(
          html.errorMsg(
            s"""Ooops! something bad happened.
        
              ${e.fullStackTrace}
            """
          )
        )
    }
  }

  def backgroundProcess = jobAlg.runStream

}

object AdminUI {

  case class AdminUIConfig(
      key: String,
      rootPath: String,
      adminTablesReadCapacity: Long,
      adminTablesWriteCapacity: Long,
      initialAdminUsername: String,
      initialRole: Role,
      siteName: String)

  implicit val roleCfgReader: ConfigReader[Role] =
    ConfigReader.fromNonEmptyString(s =>
      auth.Roles
        .fromRepr(s)
        .leftMap(_ => CannotConvert(s, "Role", "Invalid value"): FailureReason)
    )

  def loadConfig[F[_]: Sync](cfg: Config): F[AdminUIConfig] = {
    import pureconfig.generic.auto._
    ConfigSource.fromConfig(cfg).at("thomas.admin-ui").loadF[F, AdminUIConfig]
  }

  def resource[F[_]: ConcurrentEffect: Timer: Logger: ContextShift](
      implicit dc: DynamoDbAsyncClient,
      cfg: AdminUIConfig,
      config: Config,
      ec: ExecutionContext,
      ap: ArmParser[F, JValue]
    ): Resource[F, AdminUI[F]] = {

    implicit val rr = new ReverseRoutes(cfg.rootPath)

    Resource.liftF(
      dynamo.AdminDAOs.ensureAuthTables[F](
        cfg.adminTablesReadCapacity,
        cfg.adminTablesWriteCapacity
      )
    ) *>
      Resource.liftF(
        dynamo.AnalysisDAOs.ensureAnalysisTables[F](
          cfg.adminTablesReadCapacity,
          cfg.adminTablesWriteCapacity
        )
      ) *> {
      Resource.liftF(AuthDependencies[F](cfg.key)).flatMap { deps =>
        import deps._
        import dynamo.AdminDAOs._
        implicit val authAlg = AuthenticationAlg[F, BCrypt, AuthImp]
        val authUI = new UI(Some(cfg.initialAdminUsername), cfg.initialRole)

        import dynamo.AnalysisDAOs._
        import JsonMessageSubscriber._
        AbtestManagementUI.fromMongo[F](config).map { amUI =>
          new AdminUI(amUI, authUI, new analysis.UI[F], new stream.UI[F])
        }
      }
    }
  }

  def resourceFromDynamo[F[_]: ConcurrentEffect: Timer: Logger: ContextShift](
      implicit
      cfg: Config,
      adminUIConfig: AdminUIConfig,
      ec: ExecutionContext,
      ap: ArmParser[F, JValue]
    ): Resource[F, AdminUI[F]] =
    dynamo
      .client(ConfigSource.fromConfig(cfg).at("thomas.admin-ui.dynamo"))
      .flatMap { implicit dc => resource }

  /**
    * Provides a server that serves the Admin UI
    */
  def serverResourceAutoLoadConfig[F[_]: ConcurrentEffect: Timer: ContextShift](
      implicit dc: DynamoDbAsyncClient,
      executionContext: ExecutionContext,
      ap: ArmParser[F, JValue]
    ): Resource[F, ExitCode] = {
    ConfigResource.cfg[F]().flatMap { implicit c =>
      Resource.liftF(loadConfig[F](c)).flatMap { implicit cfg =>
        serverResource[F]
      }
    }
  }

  /**
    * Provides a server that serves the Admin UI
    */
  def serverResource[F[_]: ConcurrentEffect: Timer: ContextShift](
      implicit
      adminCfg: AdminUIConfig,
      config: Config,
      dc: DynamoDbAsyncClient,
      executionContext: ExecutionContext,
      ap: ArmParser[F, JValue]
    ): Resource[F, ExitCode] = {
    import org.http4s.server.blaze._
    import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
    Resource.liftF(Slf4jLogger.create[F]).flatMap { implicit logger =>
      for {
        ui <- AdminUI.resource[F]
        e <-
          BlazeServerBuilder[F](executionContext)
            .bindHttp(8080, "0.0.0.0")
            .withHttpApp(
              Router(adminCfg.rootPath -> ui.routes).orNotFound
            )
            .withServiceErrorHandler(ui.serverErrorHandler)
            .serve
            .concurrently(ui.backgroundProcess)
            .compile
            .resource
            .lastOrError

      } yield e
    }
  }
}
