package controllers

import javax.inject._
import _root_.play.api.mvc._
import cats.effect.IO

import concurrent._
import com.iheart.thomas.play._
import com.iheart.thomas.bandit.APIAlg
import org.scanamo.LocalDynamoDB

@Singleton
class MyBanditController @Inject()(
    components: ControllerComponents,
)(implicit ec: ExecutionContext)
    extends BanditController(APIAlg.default[IO], components, LocalDynamoDB.client)
