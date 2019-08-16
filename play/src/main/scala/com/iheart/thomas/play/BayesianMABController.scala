package com.iheart.thomas
package play

import java.time.Instant

import bandit._
import cats.effect.Effect
import _root_.play.api.mvc.{AbstractController, ControllerComponents, Result}
import _root_.play.api.libs.json._
import cats.effect.implicits._
import com.iheart.thomas.analysis.{Conversions, KPIName}
import com.iheart.thomas.bandit.bayesian._
import bandit.Formats._
import abtest.Formats._
import com.iheart.thomas.abtest.model.Abtest
import lihua.Entity
import cats.implicits._

import scala.concurrent.Future
import lihua.mongo.JsonFormats._

class BayesianMABController[F[_]](
    api: ConversionBMABAlg[F],
    components: ControllerComponents
)(
    implicit
    F: Effect[F]
) extends AbstractController(components) {
  import BayesianMABStateResp.fromTupple

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

  def action[Resp: Writes](ar: F[Resp]) = Action.async {
    toFutureResult(jsonResult(ar))
  }

  def updateConversions(featureName: FeatureName) =
    withJsonReq[Map[ArmName, Conversions]] { cs =>
      jsonResult(api.updateRewardState(featureName, cs))
    }

  def init =
    withJsonReq[BayesianMABInitReq] { req =>
      jsonResult(
        api
          .init(req.banditSpec, req.author, req.start)
          .map(fromTupple))
    }

  def getState(featureName: FeatureName) = action {
    api.currentState(featureName).map(fromTupple)
  }

  def reallocate(featureName: FeatureName, kpiName: KPIName) = action {
    api.reallocate(featureName, kpiName).map(fromTupple)
  }

}

case class BayesianMABInitReq(banditSpec: BanditSpec, author: String, start: Instant)

object BayesianMABInitReq {
  implicit val fmt: Format[BayesianMABInitReq] = Json.format[BayesianMABInitReq]
}

case class BayesianMABStateResp(abtest: Entity[Abtest],
                                bayesianState: BayesianState[Conversions])

object BayesianMABStateResp {
  implicit val fmt: Format[BayesianMABStateResp] = Json.format[BayesianMABStateResp]

  def fromTupple = (apply _).tupled
}
