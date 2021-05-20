package com.iheart.thomas
package dynamo

import cats.effect.{Async, Concurrent, Timer}
import cats.implicits._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeDefinition,
  CreateTableRequest,
  DescribeTableRequest,
  KeySchemaElement,
  KeyType,
  ProvisionedThroughput,
  ResourceNotFoundException,
  ScalarAttributeType
}
import com.iheart.thomas.dynamo.ScanamoDAOHelper.NotFound
import org.scanamo.ops.ScanamoOps
import org.scanamo.syntax._
import org.scanamo.{
  ConditionNotMet,
  DeleteReturn,
  DynamoFormat,
  DynamoReadError,
  ScanamoCats,
  Table
}
import io.estatico.newtype.ops._
import io.estatico.newtype.Coercible
import org.scanamo.update.UpdateExpression

import java.time.Instant
import java.util.concurrent.{
  CancellationException,
  CompletableFuture,
  CompletionException
}
import java.util.function.BiFunction
import scala.util.control.NoStackTrace

abstract class ScanamoDAOHelper[F[_], A](
    tableName: String,
    keyName: String,
    client: DynamoDbAsyncClient
  )(implicit F: Async[F],
    DA: DynamoFormat[A]) {

  protected val table = Table[A](tableName)

  protected val sc = ScanamoCats[F](client)

  protected def execTraversableOnce[TO[_], T](
      ops: ScanamoOps[TO[Either[DynamoReadError, T]]]
    )(implicit ev: TO[Either[DynamoReadError, T]] <:< TraversableOnce[
        Either[DynamoReadError, T]
      ]
    ): F[Vector[T]] =
    sc.exec(ops)
      .flatMap(t => ev(t).toVector.traverse(_.leftMap(ScanamoError(_)).liftTo[F]))

  protected def toF[E <: org.scanamo.ScanamoError, T](e: F[Either[E, T]]): F[T] =
    e.flatMap(_.leftMap(ScanamoError(_)).liftTo[F])

  protected def toF[E <: org.scanamo.ScanamoError, T](
      e: F[Option[Either[E, T]]],
      noneErr: Throwable
    ): F[T] =
    e.flatMap(_.liftTo[F](noneErr).flatMap(_.leftMap(ScanamoError(_)).liftTo[F]))

  protected def toFOption[E <: org.scanamo.ScanamoError, T](
      e: F[Option[Either[E, T]]]
    ): F[Option[T]] =
    e.flatMap(_.traverse(_.leftMap(ScanamoError(_)).liftTo[F]))

  def insert(a: A): F[A] = {
    toF(
      sc.exec(
        table
          .when(attributeNotExists(keyName))
          .put(a)
      )
    ).as(a)
  }

  def insertO(a: A): F[Option[A]] =
    insert(a).map(Option(_)).recover { case ScanamoError(ConditionNotMet(_)) =>
      None
    }

}

abstract class ScanamoDAOHelperStringLikeKey[F[_], A: DynamoFormat, K](
    tableName: String,
    keyName: String,
    client: DynamoDbAsyncClient
  )(implicit F: Async[F],
    coercible: Coercible[K, String])
    extends ScanamoDAOHelperStringFormatKey[F, A, K](
      tableName,
      keyName,
      client
    ) {
  protected def stringKey(k: K): String = k.coerce

}

abstract class ScanamoDAOHelperStringFormatKey[F[_], A: DynamoFormat, K](
    val tableName: String,
    val keyName: String,
    client: DynamoDbAsyncClient
  )(implicit F: Async[F])
    extends ScanamoDAOHelper[F, A](
      tableName,
      keyName,
      client
    ) {

  protected def stringKey(k: K): String

  def get(k: K): F[A] =
    find(k).flatMap(
      _.liftTo[F](
        NotFound(
          s"Cannot find in the table $tableName a record whose $keyName is '${stringKey(k)}'. "
        )
      )
    )

  def ensure(
      k: K
    )(ifEmpty: => F[A]
    ): F[A] =
    find(k).flatMap(
      _.fold(ifEmpty.flatMap(insert))(_.pure[F])
    )

  def find(k: K): F[Option[A]] =
    toFOption(sc.exec(table.get(keyName === stringKey(k))))

  def all: F[Vector[A]] = execTraversableOnce(table.scan())

  def remove(k: K): F[Unit] =
    sc.exec(table.delete(keyName === stringKey(k)))

  def update(a: A): F[A] =
    sc.exec(table.when(attributeExists(keyName)).put(a)).as(a)

  def upsert(a: A): F[A] =
    sc.exec(table.put(a)).as(a)

  def update(
      k: K,
      ue: UpdateExpression
    ) =
    toF(sc.exec(table.update(keyName === stringKey(k), ue)))

  def delete(k: K): F[Option[A]] =
    sc.exec(
      table
        .deleteAndReturn(DeleteReturn.OldValue)(keyName === stringKey(k))
        .map(_.flatMap(_.toOption))
    )
}

trait WithTimeStamp[-A] {
  def lastUpdated(a: A): Instant
}

trait AtomicUpdatable[F[_], A, K] {
  self: ScanamoDAOHelperStringFormatKey[F, A, K] =>
  val lastUpdatedFieldName = "lastUpdated"

  import retry._
  def atomicUpdate(
      k: K,
      retryPolicy: Option[RetryPolicy[F]] = None
    )(updateExpression: A => UpdateExpression
    )(implicit T: Timer[F],
      F: Async[F],
      A: WithTimeStamp[A]
    ): F[A] = {
    val updateF = for {
      existing <- get(k)
      now <- utils.time.now[F]
      r <- toF(
        sc.exec(
          table
            .when(lastUpdatedFieldName === A.lastUpdated(existing))
            .update(
              keyName === stringKey(k),
              updateExpression(existing)
                and set(lastUpdatedFieldName, now)
            )
        )
      )
    } yield r

    retryPolicy.fold(updateF)(rp =>
      retryingOnSomeErrors(
        rp,
        { (e: Throwable) =>
          e match {
            case ScanamoError(ConditionNotMet(_)) => true
            case _                                => false
          }
        },
        (_: Throwable, _) => Async[F].unit
      )(updateF)
    )

  }
}

abstract class ScanamoDAOHelperStringKey[F[_]: Async, A: DynamoFormat](
    tableName: String,
    keyName: String,
    client: DynamoDbAsyncClient)
    extends ScanamoDAOHelperStringLikeKey[F, A, String](tableName, keyName, client)

object ScanamoDAOHelperStringKey {
  def keyOf(keyName: String) =
    (keyName, ScalarAttributeType.S)
}

object ScanamoDAOHelper {
  sealed case class NotFound(override val getMessage: String)
      extends RuntimeException
      with NoStackTrace
      with Product
      with Serializable
}

trait ScanamoManagement {
  import scala.collection.JavaConverters._

  private def keySchema(attributes: Seq[(String, ScalarAttributeType)]) = {
    val hashKeyWithType :: rangeKeyWithType = attributes.toList
    val keySchemas = hashKeyWithType._1 -> KeyType.HASH :: rangeKeyWithType.map(
      _._1 -> KeyType.RANGE
    )
    keySchemas.map { case (symbol, keyType) =>
      KeySchemaElement.builder.attributeName(symbol).keyType(keyType).build
    }.asJava
  }

  private def lift[F[_], A](
      fcf: => CompletableFuture[A]
    )(implicit F: Concurrent[F]
    ): F[A] =
    F.delay(fcf).flatMap { cf =>
      F.cancelable(cb => {
        cf.handle[Unit](new BiFunction[A, Throwable, Unit] {
          override def apply(
              result: A,
              err: Throwable
            ): Unit =
            err match {
              case null                     => cb(Right(result))
              case _: CancellationException => ()
              case ex: CompletionException if ex.getCause ne null =>
                cb(Left(ex.getCause))
              case ex => cb(Left(ex))
            }
        })
        F.delay(cf.cancel(true)).void
      })
    }

  def createTable[F[_]: Concurrent](
      client: DynamoDbAsyncClient,
      tableName: String,
      keyAttributes: Seq[(String, ScalarAttributeType)],
      readCapacityUnits: Long,
      writeCapacityUnits: Long
    ): F[Unit] =
    lift(
      client
        .createTable(
          CreateTableRequest.builder
            .attributeDefinitions(attributeDefinitions(keyAttributes))
            .tableName(tableName)
            .keySchema(keySchema(keyAttributes))
            .provisionedThroughput(
              ProvisionedThroughput
                .builder()
                .readCapacityUnits(readCapacityUnits)
                .writeCapacityUnits(writeCapacityUnits)
                .build
            )
            .build
        )
    ).void

  def ensureTables[F[_]: Concurrent](
      tables: List[(String, (String, ScalarAttributeType))],
      readCapacityUnits: Long,
      writeCapacityUnits: Long
    )(implicit dynamo: DynamoDbAsyncClient
    ): F[Unit] =
    tables.traverse { case (tableName, keyAttribute) =>
      ensureTable(
        dynamo,
        tableName,
        Seq(keyAttribute),
        readCapacityUnits,
        writeCapacityUnits
      )
    }.void

  def ensureTable[F[_]: Concurrent](
      client: DynamoDbAsyncClient,
      tableName: String,
      keyAttributes: Seq[(String, ScalarAttributeType)],
      readCapacityUnits: Long,
      writeCapacityUnits: Long
    ): F[Unit] = {
    lift(
      client.describeTable(
        DescribeTableRequest.builder
          .tableName(tableName)
          .build
      )
    ).void.recoverWith { case _: ResourceNotFoundException =>
      createTable(
        client,
        tableName,
        keyAttributes,
        readCapacityUnits,
        writeCapacityUnits
      )
    }
  }

  private def attributeDefinitions(attributes: Seq[(String, ScalarAttributeType)]) =
    attributes.map { case (symbol, attributeType) =>
      AttributeDefinition.builder
        .attributeName(symbol)
        .attributeType(attributeType)
        .build
    }.asJava
}

object ScanamoManagement extends ScanamoManagement

case class ScanamoError(se: org.scanamo.ScanamoError)
    extends RuntimeException(se.toString)
