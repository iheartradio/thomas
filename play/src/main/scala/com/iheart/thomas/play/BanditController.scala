package com.iheart.thomas
package play

import java.time.Instant

import bandit._
import cats.effect.Effect
import _root_.play.api.mvc.{AbstractController, ControllerComponents, Result}
import _root_.play.api.libs.json.{Format, Json, Writes}
import cats.effect.implicits._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.bandit.model.{ArmState, BanditSpec, BanditState, Conversion}
import lihua.dynamo.ScanamoEntityDAO
import lihua.{EntityDAO, EntityId}
import org.scanamo.DynamoFormat

import scala.concurrent.Future

class BanditController[F[_]](
    api: APIAlg[F],
    components: ControllerComponents,
    dynamoClient: AmazonDynamoDBAsync
)(
    implicit
    F: Effect[F]
) extends AbstractController(components) {
  import BanditController._

  implicit protected def toResult[Resp: Writes](ar: F[Resp]): Future[Result] = {
    F.map(ar)(r => Ok(Json.toJson(r)))
      .toIO
      .unsafeToFuture()

  }

  implicit val stateDAO: EntityDAO[F, BanditState[Conversion], List[EntityId]] =
    new ScanamoEntityDAO[F, BanditState[Conversion]]("BanditState",
                                                     Symbol("featureName"),
                                                     dynamoClient)

  def updateConversion(featureName: FeatureName, total: Long, converted: Long) =
    Action.async {
      toResult(
        api.updateRewardState(featureName,
                              Conversion(total = total, converted = converted)))
    }
}

object BanditController {
  import org.scanamo.semiauto._

  implicit val dfInstant: DynamoFormat[Instant] =
    DynamoFormat.coercedXmap(Instant.ofEpochMilli)(_.toEpochMilli)
  implicit val dfc: DynamoFormat[BanditState[Conversion]] =
    deriveDynamoFormat[BanditState[Conversion]]

  implicit val jfC: Format[Conversion] = Json.format[Conversion]
  implicit val jfAS: Format[ArmState] = Json.format[ArmState]
  implicit val jfBS: Format[BanditSpec] = Json.format[BanditSpec]
  implicit val jfBSC: Format[BanditState[Conversion]] =
    Json.format[BanditState[Conversion]]

}
