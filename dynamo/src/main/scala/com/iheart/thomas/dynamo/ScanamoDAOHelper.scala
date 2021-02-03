package com.iheart.thomas.dynamo

import cats.effect.{Async, Timer}
import cats.implicits._
import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.dynamodbv2.model.{
  AttributeDefinition,
  CreateTableRequest,
  CreateTableResult,
  DescribeTableRequest,
  DescribeTableResult,
  KeySchemaElement,
  KeyType,
  ProvisionedThroughput,
  ResourceNotFoundException,
  ScalarAttributeType
}
import com.iheart.thomas.TimeUtil
import com.iheart.thomas.dynamo.ScanamoDAOHelper.NotFound
import lihua.dynamo.ScanamoEntityDAO.ScanamoError
import org.scanamo.ops.ScanamoOps
import org.scanamo.syntax._
import org.scanamo.{
  ConditionNotMet,
  DynamoFormat,
  DynamoReadError,
  ScanamoCats,
  Table
}
import io.estatico.newtype.ops._
import io.estatico.newtype.Coercible
import org.scanamo.update.UpdateExpression

import java.time.Instant
import scala.util.control.NoStackTrace

abstract class ScanamoDAOHelper[F[_], A](
    tableName: String,
    keyName: String,
    client: AmazonDynamoDBAsync
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
          .given(attributeNotExists(keyName))
          .put(a)
      )
    ).as(a)
  }

  def insertO(a: A): F[Option[A]] =
    insert(a).map(Option(_)).recover {
      case ScanamoError(ConditionNotMet(_)) => None
    }

}

abstract class ScanamoDAOHelperStringLikeKey[F[_], A: DynamoFormat, K](
    tableName: String,
    keyName: String,
    client: AmazonDynamoDBAsync
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
    client: AmazonDynamoDBAsync
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
          s"Cannot find in the table a record whose ${keyName} is '${stringKey(k)}'. "
        )
      )
    )

  def find(k: K): F[Option[A]] =
    toFOption(sc.exec(table.get(keyName -> stringKey(k))))

  def all: F[Vector[A]] = execTraversableOnce(table.scan())

  def remove(k: K): F[Unit] =
    sc.exec(table.delete(keyName -> stringKey(k)))

  def update(a: A): F[A] =
    sc.exec(table.given(attributeExists(keyName)).put(a)).as(a)

  def upsert(a: A): F[A] =
    sc.exec(table.put(a)).as(a)

}

trait WithTimeStamp[A] {
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
      now <- TimeUtil.now[F]
      r <- toF(
        sc.exec(
          table
            .given(lastUpdatedFieldName -> A.lastUpdated(existing))
            .update(
              keyName -> stringKey(k),
              updateExpression(existing)
                and set(lastUpdatedFieldName -> now)
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
    client: AmazonDynamoDBAsync)
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
  private def attributeDefinitions(
      attributes: Seq[(String, ScalarAttributeType)]
    ) =
    attributes.map {
      case (symbol, attributeType) =>
        new AttributeDefinition(symbol, attributeType)
    }.asJava

  private def keySchema(attributes: Seq[(String, ScalarAttributeType)]) = {
    val hashKeyWithType :: rangeKeyWithType = attributes.toList
    val keySchemas = hashKeyWithType._1 -> KeyType.HASH :: rangeKeyWithType
      .map(_._1 -> KeyType.RANGE)
    keySchemas.map {
      case (symbol, keyType) => new KeySchemaElement(symbol, keyType)
    }.asJava
  }

  private def asyncHandle[F[_], Req <: com.amazonaws.AmazonWebServiceRequest, Resp](
      f: AsyncHandler[Req, Resp] => java.util.concurrent.Future[Resp]
    )(implicit F: Async[F]
    ): F[Resp] =
    F.async { (cb: Either[Throwable, Resp] => Unit) =>
      val handler = new AsyncHandler[Req, Resp] {
        def onError(exception: Exception): Unit =
          cb(Left(exception))

        def onSuccess(
            req: Req,
            result: Resp
          ): Unit =
          cb(Right(result))
      }

      f(handler)
      ()
    }

  def createTable[F[_]](
      client: AmazonDynamoDBAsync,
      tableName: String,
      keyAttributes: Seq[(String, ScalarAttributeType)],
      readCapacityUnits: Long,
      writeCapacityUnits: Long
    )(implicit F: Async[F]
    ): F[Unit] = {
    val req = new CreateTableRequest(tableName, keySchema(keyAttributes))
      .withAttributeDefinitions(attributeDefinitions(keyAttributes))
      .withProvisionedThroughput(
        new ProvisionedThroughput(readCapacityUnits, writeCapacityUnits)
      )

    asyncHandle[F, CreateTableRequest, CreateTableResult](
      client.createTableAsync(req, _)
    ).void
  }

  def ensureTables[F[_]: Async](
      tables: List[(String, (String, ScalarAttributeType))],
      readCapacityUnits: Long,
      writeCapacityUnits: Long
    )(implicit dynamo: AmazonDynamoDBAsync
    ): F[Unit] =
    tables.traverse {
      case (tableName, keyAttribute) =>
        ensureTable(
          dynamo,
          tableName,
          Seq(keyAttribute),
          readCapacityUnits,
          writeCapacityUnits
        )
    }.void

  def ensureTable[F[_]](
      client: AmazonDynamoDBAsync,
      tableName: String,
      keyAttributes: Seq[(String, ScalarAttributeType)],
      readCapacityUnits: Long,
      writeCapacityUnits: Long
    )(implicit F: Async[F]
    ): F[Unit] = {
    asyncHandle[F, DescribeTableRequest, DescribeTableResult](
      client.describeTableAsync(new DescribeTableRequest(tableName), _)
    ).void.recoverWith {
      case e: ResourceNotFoundException =>
        createTable(
          client,
          tableName,
          keyAttributes,
          readCapacityUnits,
          writeCapacityUnits
        )
    }
  }
}
