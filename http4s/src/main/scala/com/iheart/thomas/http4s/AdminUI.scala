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
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.admin.{Role, User}
import com.typesafe.config.Config
import org.http4s.Response
import org.http4s.server.{Router, ServiceErrorHandler}
import org.http4s.server.blaze.BlazeServerBuilder
import pureconfig.error.{CannotConvert, FailureReason}
import pureconfig.{ConfigReader, ConfigSource}
import pureconfig.module.catseffect._
import tsec.common.SecureRandomIdGenerator
import cats.MonadThrow
import com.iheart.thomas.kafka.JsonConsumer
import com.iheart.thomas.stream.JobAlg
import io.chrisdavenport.log4cats.Logger

import scala.concurrent.ExecutionContext
import org.http4s.twirl._
import org.typelevel.jawn.ast.JValue
import tsec.authentication.Authenticator
import tsec.passwordhashers.jca.BCrypt
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

import java.io.{PrintWriter, StringWriter}

class AdminUI[F[_]: MonadThrow](
    abtestManagementUI: AbtestManagementUI[F],
    authUI: auth.UI[F, AuthImp],
    analysisUI: analysis.UI[F],
    streamUI: stream.UI[F]
  )(implicit reverseRoutes: ReverseRoutes,
    jobAlg: JobAlg[F, JValue],
    authenticator: Authenticator[F, String, User, Token[AuthImp]])
    extends AuthedEndpointsUtils[F, AuthImp]
    with Http4sDsl[F] {

  val routes = authUI.publicEndpoints <+> liftService(
    abtestManagementUI.routes <+> authUI.authedService <+> analysisUI.routes <+> streamUI.routes
  )

  def fullStackTrace(t: Throwable): String = {
    val sw = new StringWriter
    t.printStackTrace(new PrintWriter(sw))
    sw.toString
  }

  val serverErrorHandler: ServiceErrorHandler[F] = { _ =>
    {
      case admin.Authorization.LackPermission =>
        Response[F](Unauthorized).pure[F]
      case e =>
        InternalServerError(
          html.errorMsg(
            s"""Ooops! something bad happened.
        
              ${fullStackTrace(e)}
            """
          )
        )
    }
  }

  def backgroundProcess(
      cfg: Config
    )(implicit ce: ConcurrentEffect[F],
      t: Timer[F],
      cs: ContextShift[F],
      l: Logger[F]
    ): Stream[F, Unit] =
    JsonConsumer.jobStream(cfg)

}

object AdminUI {
  def generateKey: String =
    SecureRandomIdGenerator(256).generate

  case class AdminUIConfig(
      key: String,
      rootPath: String,
      adminTablesReadCapacity: Long,
      adminTablesWriteCapacity: Long,
      initialAdminUsername: String,
      initialRole: Role)

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

  def resource[F[_]: Concurrent: Timer](
      cfg: AdminUIConfig,
      mongoAbtest: Config
    )(implicit dc: AmazonDynamoDBAsync,
      ec: ExecutionContext
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

        AbtestManagementUI.fromMongo[F](mongoAbtest).map { amUI =>
          new AdminUI(amUI, authUI, new analysis.UI[F], new stream.UI[F])
        }
      }
    }
  }

  def resource[F[_]: ConcurrentEffect: Timer](
      cfg: Config
    )(implicit
      ec: ExecutionContext
    ): Resource[F, AdminUI[F]] =
    dynamo
      .client(ConfigSource.fromConfig(cfg).at("thomas.admin-ui.dynamo"))
      .flatMap { implicit dc =>
        Resource.liftF(AdminUI.loadConfig[F](cfg)).flatMap { adminCfg =>
          resource(adminCfg, cfg)
        }
      }

  /**
    * Provides a server that serves the Admin UI
    */
  def serve[F[_]: ConcurrentEffect: Timer: ContextShift](
      implicit dc: AmazonDynamoDBAsync,
      executionContext: ExecutionContext
    ): Stream[F, ExitCode] = {
    Stream.resource(ConfigResource.cfg[F]()).flatMap(serve(_))
  }

  /**
    * Provides a server that serves the Admin UI
    */
  def serve[F[_]: ConcurrentEffect: Timer: ContextShift](
      cfg: Config
    )(implicit
      dc: AmazonDynamoDBAsync,
      executionContext: ExecutionContext
    ): Stream[F, ExitCode] = {
    import org.http4s.server.blaze._
    import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
    Stream.eval(Slf4jLogger.create[F]).flatMap { implicit logger =>
      for {
        adminCfg <- Stream.eval(AdminUI.loadConfig[F](cfg))
        ui <- Stream.resource(AdminUI.resource[F](adminCfg, cfg))
        e <-
          BlazeServerBuilder[F](executionContext)
            .bindHttp(8080, "0.0.0.0")
            .withHttpApp(
              Router(adminCfg.rootPath -> ui.routes).orNotFound
            )
            .withServiceErrorHandler(ui.serverErrorHandler)
            .serve
            .concurrently(ui.backgroundProcess(cfg).handleErrorWith(_ => Stream(())))

      } yield e
    }
  }
}
