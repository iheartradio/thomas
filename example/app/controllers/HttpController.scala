/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package controllers

import javax.inject._
import play.api.mvc._

import concurrent._
import com.iheart.thomas.http.lib._

@Singleton
class HttpController @Inject() (
  provider:      APIProvider,
  components:    ControllerComponents,
)(implicit ec: ExecutionContext) extends AbtestController(provider.api, provider.kpiApi, components, None)
