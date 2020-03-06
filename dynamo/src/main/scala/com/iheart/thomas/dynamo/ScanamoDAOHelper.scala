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
      .flatMap(
        t => ev(t).toVector.traverse(_.leftMap(ScanamoError(_)).liftTo[F])
      )

  protected def toF[E <: org.scanamo.ScanamoError, T](e: F[Either[E, T]]): F[T] =
    e.flatMap(_.leftMap(ScanamoError(_)).liftTo[F])

  protected def toF[E <: org.scanamo.ScanamoError, T](
      e: F[Option[Either[E, T]]],
      noneErr: Throwable
    ): F[T] =
    e.flatMap(_.liftTo[F](noneErr).flatMap(_.leftMap(ScanamoError(_)).liftTo[F]))

  def insert(a: A): F[A] =
    toF(
      sc.exec(
          table
            .given(attributeNotExists(keyName))
            .putAndReturn(Nothing)(a)
        )
        .map(_.getOrElse(a.asRight))
    )

}

abstract class ScanamoDAOHelperStringKey[F[_], A: DynamoFormat](
    tableName: String,
    keyName: String,
    client: AmazonDynamoDBAsync
  )(implicit F: Async[F])
    extends ScanamoDAOHelper[F, A](
      tableName,
      keyName,
      client
    ) {

  def get(k: String): F[A] =
    toF(
      sc.exec(table.get(keyName -> k)),
      NotFound(
        s"Cannot find in the table a record whose ${keyName} is '$k'. "
      )
    )

  def remove(k: String): F[Unit] =
    sc.exec(table.delete(keyName -> k)).void

}

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
