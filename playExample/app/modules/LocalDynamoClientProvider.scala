package modules

import cats.effect.IO
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.dynamo.DAOs
import com.iheart.thomas.play.DynamoClientProvider
import lihua.dynamo.ScanamoManagement
import org.scanamo.LocalDynamoDB
object LocalDynamoClientProvider
    extends DynamoClientProvider
    with ScanamoManagement {
  lazy val get: AmazonDynamoDBAsync = {
    implicit val client = LocalDynamoDB.client()
    DAOs.ensureBanditTables[IO](1L, 1L).unsafeRunSync()
    client
  }
}
