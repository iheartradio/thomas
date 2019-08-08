package controllers

import javax.inject._
import _root_.play.api.mvc._
import cats.effect.IO

import concurrent._
import com.iheart.thomas.play._
import com.iheart.thomas.bandit.SingleArmBanditAPIAlg
import org.scanamo.LocalDynamoDB

@Singleton
class MyBayesianMABController @Inject()(
    components: ControllerComponents,
)(implicit ec: ExecutionContext)
    extends BayesianMABController(SingleArmBanditAPIAlg.default[IO],
                                  components,
                                  LocalDynamoDB.client)
