package com.iheart.thomas
package http4s
package analysis

import cats.effect.Async
import com.iheart.thomas.analysis.MessageQuery.{FieldName, FieldValue}
import com.iheart.thomas.analysis.{
  BetaModel,
  ConversionKPI,
  ConversionKPIAlg,
  ConversionMessageQuery,
  KPIName,
  MessageQuery
}
import com.iheart.thomas.http4s.{AuthImp, ReverseRoutes}
import com.iheart.thomas.http4s.auth.{AuthedEndpointsUtils, AuthenticationAlg}
import org.http4s.dsl.Http4sDsl
import cats.implicits._
import org.http4s.twirl._
import com.iheart.thomas.analysis.html._
import com.iheart.thomas.html.{errorMsg, redirect}
import org.http4s.FormDataDecoder
import FormDataDecoder._
import com.iheart.thomas.http4s.AdminUI.AdminUIConfig
import com.iheart.thomas.http4s.analysis.UI.UpdateKPIRequest
import com.iheart.thomas.stream.JobAlg
import com.iheart.thomas.stream.JobSpec.UpdateKPIPrior
import tsec.authentication._

import java.time.{Instant, OffsetDateTime}

class UI[F[_]: Async](
    implicit
    convKpiAlg: ConversionKPIAlg[F],
    jobAlg: JobAlg[F],
    authAlg: AuthenticationAlg[F, AuthImp],
    aCfg: AdminUIConfig)
    extends AuthedEndpointsUtils[F, AuthImp]
    with Http4sDsl[F] {
  val reverseRoutes = ReverseRoutes(aCfg)
  import UI.Decoders._
  val rootPath = Root / "analysis"
  val readonlyRoutes = roleBasedService(admin.Authorization.readableRoles) {
    case GET -> `rootPath` / "conversionKPIs" asAuthed (u) =>
      convKpiAlg.all.flatMap { kpis =>
        Ok(conversionKPIs(kpis)(UIEnv(u)))
      }
  }

  val managingRoutes = roleBasedService(admin.Authorization.analysisManagerRoles) {
    case GET -> `rootPath` / "conversionKPI" / "new" asAuthed (u) =>
      Ok(newConversionKPI()(UIEnv(u)))

    case GET -> `rootPath` / "conversionKPIs" / kpiName asAuthed (u) =>
      convKpiAlg.find(kpiName).flatMap { ko =>
        ko.fold(
          NotFound(s"Cannot find the Conversion KPI under the name $kpiName")
        ) { k =>
          jobAlg.find(UpdateKPIPrior(kpiName, Instant.MIN)).flatMap { jobO =>
            Ok(editConversionKPI(k, jobO)(UIEnv(u)))
          }
        }
      }

    case GET -> `rootPath` / "conversionKPIs" / kpiName / "delete" asAuthed (_) =>
      convKpiAlg.remove(kpiName) >>
        Ok(redirect(reverseRoutes.analysis, s"$kpiName, if existed, is deleted."))

    case se @ POST -> `rootPath` / "conversionKPIs" asAuthed u =>
      se.request
        .as[ConversionKPI]
        .redeemWith(
          e => BadRequest(errorMsg(e.getMessage)),
          kpi =>
            convKpiAlg.create(kpi.copy(author = u.username)) >>
              Ok(
                redirect(
                  reverseRoutes.analysis,
                  s"Conversion KPI ${kpi.name} successfully created."
                )
              )
        )

    case se @ POST -> `rootPath` / "conversionKPIs" / kpiName asAuthed u =>
      se.request
        .as[ConversionKPI]
        .redeemWith(
          e => BadRequest(errorMsg(e.getMessage)),
          kpi =>
            if (kpi.name.n != kpiName) {
              BadGateway("Cannot change KPI name")
            } else
              convKpiAlg.update(kpi.copy(author = u.username)) >>
                Ok(
                  redirect(
                    reverseRoutes.analysis,
                    s"Conversion KPI ${kpi.name} successfully updated."
                  )
                )
        )

    case se @ POST -> `rootPath` / "conversionKPIs" / kpiName / "update-prior" asAuthed u =>
      se.request.as[UpdateKPIRequest].flatMap { r =>
        jobAlg.schedule(UpdateKPIPrior(kpiName, r.until.toInstant)).flatMap { jo =>
          jo.fold(
            BadRequest(errorMsg("It's being updated right now"))
          )(j =>
            Ok(
              redirect(
                reverseRoutes.analysis + "/" + kpiName,
                s"Scheduled a background process to update the prior using ongoing data. "
              )
            )
          )
        }
      }
  }

  val routes = readonlyRoutes <+> managingRoutes

}

object UI {

  case class UpdateKPIRequest(until: OffsetDateTime)

  object Decoders {

    import CommonFormDecoders._
    implicit val messageQueryDecoder: FormDataDecoder[MessageQuery] = (
      fieldOptional[String]("description"),
      list[(FieldName, FieldValue)]("criteria")
    ).mapN(MessageQuery.apply)

    implicit val uprDecoder: FormDataDecoder[UpdateKPIRequest] =
      field[OffsetDateTime]("until").map(UpdateKPIRequest(_))

    implicit val conversionMessageQueryDecoder
        : FormDataDecoder[ConversionMessageQuery] = (
      nested[MessageQuery]("initMessage"),
      nested[MessageQuery]("convertedMessage")
    ).mapN(ConversionMessageQuery.apply)

    implicit val betaModelDecoder: FormDataDecoder[BetaModel] = (
      field[Double]("alphaPrior"),
      field[Double]("betaPrior")
    ).mapN(BetaModel.apply)

    implicit val conversionKPIDecoder: FormDataDecoder[ConversionKPI] = (
      field[KPIName]("name"),
      field[String]("author"),
      fieldOptional[String]("description"),
      nested[BetaModel]("model"),
      nestedOptional[ConversionMessageQuery]("messageQuery")
    ).mapN(ConversionKPI.apply).sanitized
  }
}
