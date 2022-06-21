package com.iheart.thomas
package http4s
package abtest

import cats.effect.Async
import com.iheart.thomas.abtest.AbtestAlg
import com.iheart.thomas.http4s.auth.AuthedEndpointsUtils
import org.http4s.{EntityDecoder}
import org.http4s.dsl.Http4sDsl
import com.iheart.thomas.abtest.model._
import cats.implicits._
import com.iheart.thomas.abtest.protocol.UpdateUserMetaCriteriaRequest
import lihua.EntityId
import com.iheart.thomas.abtest.json.play.Formats._
import lihua.mongo.JsonFormats._
import org.http4s.play.jsonOf
import play.api.libs.json.Reads

class AbtestManagementService[F[_]: Async](
    private[http4s] val api: AbtestAlg[F])
    extends AuthedEndpointsUtils[F, AuthImp]
    with Http4sDsl[F]
    with AbtestServiceHelper[F] {
  implicit def decoder[A: Reads]: EntityDecoder[F, A] = jsonOf
  import tsec.authentication._
  import AbtestService.QueryParamDecoderMatchers._
  val routes = roleBasedService(admin.Authorization.testManagerRoles) {

    case req @ POST -> Root / "tests" :? auto(a) asAuthed _ =>
      req.request.as[AbtestSpec] >>= (t =>
        respond(api.create(t, a.getOrElse(false)))
      )

    case req @ POST -> Root / "tests" / "auto" asAuthed _ =>
      req.request.as[AbtestSpec] >>= (t => respond(api.create(t, true)))

    case req @ PUT -> Root / "tests" asAuthed _ =>
      req.request.as[AbtestSpec] >>= (t => respond(api.continue(t)))

    case DELETE -> Root / "tests" / testId asAuthed _ =>
      respondOption(
        api.terminate(EntityId(testId)),
        s"No test with id $testId"
      )

    case req @ PUT -> Root / "tests" / testId / "groups" / "metas" :? auto(
          a
        ) asAuthed _ =>
      req.request.as[Map[GroupName, GroupMeta]] >>= (m =>
        respond(
          api.addGroupMetas(EntityId(testId), m, a.getOrElse(false))
        )
      )

    case DELETE -> Root / "tests" / testId / "groups" / "metas" :? auto(
          a
        ) asAuthed _ =>
      respond(
        api.removeGroupMetas(EntityId(testId), a.getOrElse(false))
      )

    case PUT -> Root / "features" / feature / "groups" / groupName / "overrides" / userId asAuthed _ =>
      respond(api.addOverrides(feature, Map(userId -> groupName)))

    case PUT -> Root / "features" / feature / "overridesEligibility" :? ovrrd(
          o
        ) asAuthed _ =>
      respond(api.setOverrideEligibilityIn(feature, o))

    case DELETE -> Root / "features" / feature / "overrides" / userId asAuthed _ =>
      respond(api.removeOverrides(feature, userId))

    case DELETE -> Root / "features" / feature / "overrides" asAuthed _ =>
      respond(api.removeAllOverrides(feature))

    case req @ POST -> Root / "features" / feature / "overrides" asAuthed _ =>
      req.request.as[Map[UserId, GroupName]] >>= (m =>
        respond(api.addOverrides(feature, m))
      )

    case req @ PUT -> Root / "tests" / testId / "userMetaCriteria" asAuthed _ =>
      req.request.as[UpdateUserMetaCriteriaRequest] >>= { r =>
        respond(
          api.updateUserMetaCriteria(EntityId(testId), r.criteria, r.auto)
        )
      }
  }
}
