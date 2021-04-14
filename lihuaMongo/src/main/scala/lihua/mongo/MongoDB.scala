package lihua
package mongo

import com.typesafe.config.{Config, ConfigFactory}
import reactivemongo.api._
import cats.effect.{Async, ContextShift, IO, Resource}

import scala.concurrent.{ExecutionContext, Future}
import net.ceedubs.ficus.Ficus._
import cats.implicits._
import net.ceedubs.ficus.readers.namemappers.implicits.hyphenCase
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader

import scala.concurrent.duration.FiniteDuration
import concurrent.duration._
import reactivemongo.api.bson.collection.BSONCollection

/**
  * A MongoDB instance from config
  * Should be created one per application
  */
class MongoDB[F[_]: Async] private (
    private[mongo] val config: MongoDB.MongoConfig,
    connection: MongoConnection,
    private[mongo] val driver: AsyncDriver) {
  private def database(
      databaseName: String
    )(implicit ec: ExecutionContext
    ): F[DB] = {
    val dbConfigO = config.dbs.get(s"$databaseName")
    val name = dbConfigO.flatMap(_.name).getOrElse(databaseName)
    implicit val cs = IO.contextShift(ec)
    toF(connection.database(name))
  }

  def collection(
      dbName: String,
      collectionName: String
    )(implicit ec: ExecutionContext
    ): F[BSONCollection] = {
    val collectionConfig =
      config.dbs.get(dbName).flatMap(_.collections.get(collectionName))
    val name = collectionConfig.flatMap(_.name).getOrElse(collectionName)
    val readPreference = collectionConfig.flatMap(_.readPreference)
    database(dbName).map(
      db =>
        db.collection[BSONCollection](
            name,
            db.failoverStrategy
          )
          .withReadPreference(
            readPreference.getOrElse(ReadPreference.primary)
          )
    )
  }

  def close(
      to: FiniteDuration = 2.seconds
    )(implicit ex: ExecutionContext
    ): F[Unit] = {
    implicit val cs = IO.contextShift(ex)
    toF(driver.close(to))
  }

  protected def toF[B](
      f: => Future[B]
    )(implicit ec: ContextShift[IO]
    ): F[B] =
    IO.fromFuture(IO(f)).to[F]
}

object MongoDB {
  def apply[F[_]](
      rootConfig: Config,
      cryptO: Option[Crypt[F]] = None
    )(implicit F: Async[F],
      sh: ShutdownHook = ShutdownHook.ignore,
      ec: ExecutionContext
    ): F[MongoDB[F]] = {
    implicit val cs = IO.contextShift(ec)
    for {
      config <- F
        .delay(rootConfig.as[MongoConfig]("mongoDB"))
        .ensure(
          new MongoDBConfigurationException(
            "mongoDB.hosts must be set in the conf"
          )
        )(_.hosts.nonEmpty)

      creds <- credOf(config, cryptO)
      mongoCfg <- F.delay(
        rootConfig
          .withFallback(
            ConfigFactory.parseResources("default-reactive-mongo.conf")
          )
          .resolve
      )
      d <- F.delay(AsyncDriver(mongoCfg, this.getClass.getClassLoader))
      options = MongoConnectionOptions.default.copy(
        sslEnabled = config.sslEnabled,
        authenticationDatabase = config.authSource,
        sslAllowsInvalidCert = true,
        authenticationMechanism = config.authMode,
        readPreference =
          config.readPreference.getOrElse(ReadPreference.primaryPreferred),
        failoverStrategy = FailoverStrategy.default.copy(
          initialDelay = config.initialDelay
            .getOrElse(FailoverStrategy.default.initialDelay),
          retries =
            config.retries.getOrElse(FailoverStrategy.default.retries)
        ),
        credentials = creds
      )
      connection <- IO
        .fromFuture(IO(d.connect(config.hosts, options)))
        .to[F]
    } yield {
      val mongoDB = new MongoDB(config, connection, d)
      sh.onShutdown(mongoDB.driver.close())
      mongoDB
    }
  }

  def resource[F[_]](
      rootConfig: Config,
      cryptO: Option[Crypt[F]] = None
    )(implicit F: Async[F],
      ec: ExecutionContext
    ): Resource[F, MongoDB[F]] =
    Resource.make(MongoDB(rootConfig, cryptO))(_.close(10.seconds))

  private[mongo] def credOf[F[_]](
      config: MongoConfig,
      cryptO: Option[Crypt[F]]
    )(implicit F: Async[F]
    ): F[Map[String, MongoConnectionOptions.Credential]] =
    config.dbs.toList
      .traverse {
        case (k, dbc) =>
          dbc.credential.traverse { c =>
            cryptO
              .fold(F.pure(c.password))(_.decrypt(c.password))
              .map(
                p =>
                  (
                    dbc.name.getOrElse(k),
                    MongoConnectionOptions.Credential(c.username, Some(p))
                  )
              )
          }
      }
      .map { l =>
        val map = l.flatten.toMap
        //if authSource db is missing a credential, use the top one by default.
        (config.authSource, map.values.headOption).tupled
          .fold(map)(Map(_) ++ map)
      }

  class MongoDBConfigurationException(msg: String) extends Exception(msg)

  case class MongoConfig(
      hosts: List[String] = Nil,
      sslEnabled: Boolean = false,
      authSource: Option[String] = None,
      authMode: AuthenticationMode = ScramSha1Authentication,
      dbs: Map[String, DBConfig] = Map(),
      readPreference: Option[ReadPreference],
      initialDelay: Option[FiniteDuration],
      retries: Option[Int])

  case class DBConfig(
      name: Option[String],
      credential: Option[Credential],
      collections: Map[String, CollectionConfig] = Map())

  case class Credential(
      username: String,
      password: String)

  case class CollectionConfig(
      name: Option[String],
      readPreference: Option[ReadPreference])

  implicit val readPreferenceValueReader: ValueReader[ReadPreference] =
    new ValueReader[ReadPreference] {
      def read(
          config: Config,
          path: String
        ): ReadPreference = config.getString(path) match {
        case "secondary"           => ReadPreference.secondary
        case "secondary-preferred" => ReadPreference.secondaryPreferred
        case "primary"             => ReadPreference.primary
        case "primary-preferred"   => ReadPreference.primaryPreferred
        case s =>
          throw new MongoDBConfigurationException(
            s + " is not a recognized read preference"
          )
      }
    }

  implicit val authenticationModeReader: ValueReader[AuthenticationMode] =
    new ValueReader[AuthenticationMode] {
      def read(
          config: Config,
          path: String
        ): AuthenticationMode = config.getString(path) match {
        case "X509"          => X509Authentication
        case "SCRAM-SHA-256" => ScramSha256Authentication
        case "SCRAM-SHA-1"   => ScramSha1Authentication
        case s =>
          throw new MongoDBConfigurationException(
            s + " is not a recognized Authentication Mode, Options are: X509, SCRAM-SHA-1, SCRAM-SHA-256"
          )
      }
    }
}
