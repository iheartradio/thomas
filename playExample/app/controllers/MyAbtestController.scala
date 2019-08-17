/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package controllers

import javax.inject._
import _root_.play.api.mvc._

import concurrent._
import com.iheart.thomas.play._

@Singleton
class MyAbtestController @Inject()(
    provider: AbtestAPIProvider,
    components: ControllerComponents,
)(implicit ec: ExecutionContext)
    extends AbtestController(provider.api, provider.kpiApi, components, None)
