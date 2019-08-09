package controllers

import javax.inject._
import _root_.play.api.mvc._
import cats.effect.IO
import com.iheart.thomas.bandit.single.SingleChoiceBanditAPIAlg

import concurrent._
import com.iheart.thomas.play._
import org.scanamo.LocalDynamoDB

@Singleton
class MyBayesianMABController @Inject()(
    components: ControllerComponents,
)(implicit ec: ExecutionContext)
    extends BayesianMABController(SingleChoiceBanditAPIAlg.default[IO],
                                  components,
                                  LocalDynamoDB.client)
