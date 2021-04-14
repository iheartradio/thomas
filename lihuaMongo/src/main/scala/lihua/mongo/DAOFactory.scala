package lihua
package mongo

import cats.effect.{Async, IO}
import play.api.libs.json.Format
import cats.implicits._
import reactivemongo.api.Collation
import reactivemongo.api.bson.BSONDocument
import reactivemongo.api.indexes.{Index, IndexType}

import scala.concurrent.ExecutionContext
import reactivemongo.api.bson.collection.BSONCollection
import reactivemongo.api.commands.CommandException

trait DAOFactory[F[_], DAOF[_], A] {
  def create(
      implicit mongoDB: MongoDB[F],
      ec: ExecutionContext
    ): F[EntityDAO[DAOF, A, Query]]
}

abstract class DAOFactoryWithEnsure[A: Format, DAOF[_], F[_]](
    dbName: String,
    collectionName: String
  )(implicit F: Async[F])
    extends DAOFactory[F, DAOF, A] {
  protected def ensure(collection: BSONCollection): F[Unit]

  private def ensureCollection(
      collection: BSONCollection
    )(implicit ec: ExecutionContext
    ): F[Unit] = {
    implicit val cs = IO.contextShift(ec)
    F.liftIO(IO.fromFuture(IO(collection.create().recover {
      case CommandException.Code(48 /*NamespaceExists*/ ) => ()
    })))
  }

  def create(
      implicit mongoDB: MongoDB[F],
      ec: ExecutionContext
    ): F[EntityDAO[DAOF, A, Query]] = {
    for {
      c <- mongoDB.collection(dbName, collectionName)
      _ <- ensureCollection(c)
      _ <- ensure(c)
      dao <- doCreate(c)
    } yield dao
  }

  def doCreate(
      c: BSONCollection
    )(implicit ec: ExecutionContext
    ): F[EntityDAO[DAOF, A, Query]]

  //replacement for the deprecated Index.apply
  protected def index(
      key: Seq[(String, IndexType)],
      name: Option[String] = None,
      unique: Boolean = false,
      background: Boolean = false,
      sparse: Boolean = false,
      expireAfterSeconds: Option[Int] = None,
      defaultLanguage: Option[String] = None,
      languageOverride: Option[String] = None,
      textIndexVersion: Option[Int] = None,
      sphereIndexVersion: Option[Int] = None,
      bits: Option[Int] = None,
      min: Option[Double] = None,
      max: Option[Double] = None,
      bucketSize: Option[Double] = None,
      collation: Option[Collation] = None,
      version: Option[Int] = None
    ): Index.Default =
    Index(
      key = key,
      name = name,
      unique = unique,
      background = background,
      sparse = sparse,
      expireAfterSeconds = expireAfterSeconds,
      storageEngine = None,
      weights = None,
      defaultLanguage = defaultLanguage,
      languageOverride = languageOverride,
      textIndexVersion = textIndexVersion,
      sphereIndexVersion = sphereIndexVersion,
      bits = bits,
      min = min,
      max = max,
      bucketSize = bucketSize,
      collation = collation,
      wildcardProjection = None,
      version = version,
      None,
      BSONDocument.empty
    )
}

abstract class DirectDAOFactory[A: Format, F[_]](
    dbName: String,
    collectionName: String
  )(implicit F: Async[F])
    extends DAOFactoryWithEnsure[A, F, F](dbName, collectionName) {
  def doCreate(
      c: BSONCollection
    )(implicit ec: ExecutionContext
    ): F[EntityDAO[F, A, Query]] =
    F.delay(AsyncEntityDAO.direct[F, A](new AsyncEntityDAO(c)))
}

abstract class EitherTDAOFactory[A: Format, F[_]](
    dbName: String,
    collectionName: String
  )(implicit F: Async[F])
    extends DAOFactoryWithEnsure[A, AsyncEntityDAO.Result[F, ?], F](
      dbName,
      collectionName
    ) {
  def doCreate(
      c: BSONCollection
    )(implicit ec: ExecutionContext
    ): F[EntityDAO[AsyncEntityDAO.Result[F, ?], A, Query]] =
    F.pure(new AsyncEntityDAO[A, F](c))
}
