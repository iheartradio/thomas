package com.iheart.thomas.dynamo

import cats.effect.Async
import cats.implicits._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType
import com.iheart.thomas.dynamo.ScanamoDAOHelper.NotFound
import lihua.dynamo.ScanamoEntityDAO.ScanamoError
import org.scanamo.PutReturn.Nothing
import org.scanamo.ops.ScanamoOps
import org.scanamo.syntax._
import org.scanamo.{DynamoFormat, DynamoReadError, ScanamoCats, Table}
import io.estatico.newtype.ops._
import io.estatico.newtype.Coercible
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
            .putAndReturn(Nothing)(a)
        )
        .map(_.getOrElse(a.asRight))
    )
  }

}

abstract class ScanamoDAOHelperStringLikeKey[F[_], A: DynamoFormat, K](
    tableName: String,
    keyName: String,
    client: AmazonDynamoDBAsync
  )(implicit F: Async[F],
    coercible: Coercible[K, String])
    extends ScanamoDAOHelper[F, A](
      tableName,
      keyName,
      client
    ) {

  def get(k: K): F[A] =
    find(k).flatMap(
      _.liftTo[F](
        NotFound(
          s"Cannot find in the table a record whose ${keyName} is '${k.coerce[String]}'. "
        )
      )
    )

  def find(k: K): F[Option[A]] =
    toFOption(sc.exec(table.get(keyName -> k.coerce)))

  def all: F[Vector[A]] = execTraversableOnce(table.scan())

  def remove(k: K): F[Unit] =
    sc.exec(table.delete(keyName -> k.coerce)).void

  def update(a: A): F[A] =
    sc.exec(table.given(attributeExists(keyName)).put(a)).as(a)

  def upsert(a: A): F[A] =
    sc.exec(table.put(a)).as(a)

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
