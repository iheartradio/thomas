package controllers

import javax.inject._
import _root_.play.api.mvc._

import concurrent._
import com.iheart.thomas.play._
import modules.LocalDynamoClientProvider

@Singleton
class MyBayesianMABController @Inject()(
    provider: AbtestAPIProvider,
    components: ControllerComponents
  )(implicit ec: ExecutionContext)
    extends BayesianMABController(
      new BanditAlgsProvider(provider, LocalDynamoClientProvider).conversionBMABAlg,
      components
    )
