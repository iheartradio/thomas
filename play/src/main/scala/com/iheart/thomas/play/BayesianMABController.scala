package com.iheart.thomas
package play

import bandit._
import cats.effect.Effect
import _root_.play.api.mvc.{AbstractController, ControllerComponents, Result}
import _root_.play.api.libs.json._
import cats.effect.implicits._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.iheart.thomas.analysis.Conversions
import com.iheart.thomas.bandit.bayesian._
import bandit.Formats._

import scala.concurrent.Future

class BayesianMABController[F[_]](
    api: ConversionBMABAlg[F],
    components: ControllerComponents,
    dynamoClient: AmazonDynamoDBAsync
)(
    implicit
    F: Effect[F]
) extends AbstractController(components) {

  protected def withJsonReq[ReqT: Reads](f: ReqT => F[Result]) =
    Action.async[JsValue](parse.tolerantJson) { req =>
      req.body
        .validate[ReqT]
        .fold(
          errs => Future.successful(BadRequest(errs.map(_.toString).mkString("\n"))),
          t => f(t).toIO.unsafeToFuture()
        )
    }

  implicit protected def toFutureResult(ar: F[Result]): Future[Result] = {
    ar.toIO.unsafeToFuture()
  }

  implicit protected def jsonResult[Resp: Writes](ar: F[Resp]): F[Result] = {
    F.map(ar)(r => Ok(Json.toJson(r)))
  }

  def updateConversions(featureName: FeatureName) =
    withJsonReq[Map[ArmName, Conversions]] { cs =>
      jsonResult(api.updateRewardState(featureName, cs))
    }
}
