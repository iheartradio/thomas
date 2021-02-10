package com.iheart.thomas
package testkit

import cats.Parallel
import cats.effect.{Concurrent, Resource, Sync}
import com.iheart.thomas.dynamo.ScanamoManagement
import org.scanamo.LocalDynamoDB
import cats.implicits._
import software.amazon.awssdk.services.dynamodb.model.{
  ResourceNotFoundException,
  ScalarAttributeType
}

object LocalDynamo extends ScanamoManagement {
  def client[F[_]](implicit F: Sync[F]) =
    Resource.make {
      F.delay(LocalDynamoDB.client())
    } { client =>
      F.delay(client.close())
    }

  def clientWithTables[F[_]: Parallel](
      tables: (String, Seq[(String, ScalarAttributeType)])*
    )(implicit F: Concurrent[F]
    ) =
    client[F].flatTap { client =>
      Resource.make {
        tables.toList.parTraverse {
          case (tableName, keyAttributes) =>
            ensureTable[F](client, tableName, keyAttributes, 10L, 10L)
        }
      } { _ =>
        tables.toList.parTraverse { t =>
          F.delay(LocalDynamoDB.deleteTable(client)(t._1)).void.recover {
            case e: ResourceNotFoundException => ()
          }
        }.void
      }
    }
}
