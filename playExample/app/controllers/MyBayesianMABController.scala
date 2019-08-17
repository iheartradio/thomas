package controllers

import javax.inject._
import _root_.play.api.mvc._

import concurrent._
import com.iheart.thomas.play._

@Singleton
class MyBayesianMABController @Inject()(
    apiProvider: BanditAlgsProvider,
    components: ControllerComponents,
)(implicit ec: ExecutionContext)
    extends BayesianMABController(apiProvider.conversionBMABAlg, components)
