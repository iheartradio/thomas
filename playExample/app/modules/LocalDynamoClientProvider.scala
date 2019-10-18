package modules

import cats.effect.IO
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.dynamo
import com.iheart.thomas.play.DynamoClientProvider
import lihua.dynamo.ScanamoEntityDAO
import org.scanamo.LocalDynamoDB

object LocalDynamoClientProvider extends DynamoClientProvider {
  lazy val get: AmazonDynamoDBAsync = {
    val client = LocalDynamoDB.client()
    ScanamoEntityDAO
      .ensureTable[IO](client, dynamo.DAOs.banditStateTableName)
      .unsafeRunSync()
    client
  }
}
