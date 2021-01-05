package com.iheart.thomas
package http4s
package analysis

import cats.effect.Async
import com.iheart.thomas.analysis.MessageQuery.{FieldName, FieldValue}
import com.iheart.thomas.analysis.{
  BetaModel,
  ConversionKPI,
  ConversionKPIDAO,
  ConversionMessageQuery,
  KPIName,
  MessageQuery
}
import com.iheart.thomas.http4s.{AuthImp, ReverseRoutes}
import com.iheart.thomas.http4s.auth.{AuthedEndpointsUtils, AuthenticationAlg}
import org.http4s.dsl.Http4sDsl
import cats.implicits._
import com.iheart.thomas.analysis.html._
import com.iheart.thomas.html.{errorMsg, redirect}
import org.http4s.twirl._
import org.http4s.FormDataDecoder, FormDataDecoder._
class UI[F[_]: Async](
    implicit
    conversionKPIDAO: ConversionKPIDAO[F],
    authAlg: AuthenticationAlg[F, AuthImp],
    reverseRoutes: ReverseRoutes)
    extends AuthedEndpointsUtils[F, AuthImp]
    with Http4sDsl[F] {

  import UI.Decoders._
  import tsec.authentication._
  val rootPath = Root / "analysis"
  val readonlyRoutes = roleBasedService(admin.Authorization.readableRoles) {
    case GET -> rootPath / "conversionKPIs" asAuthed (u) =>
      conversionKPIDAO.all.flatMap { kpis =>
        Ok(conversionKPIs(kpis, u))
      }
  }

  val managingRoutes = roleBasedService(admin.Authorization.analysisManagerRoles) {
    case GET -> rootPath / "conversionKPI" / "new" asAuthed (u) =>
      Ok(conversionKPIForm(None, u))
    case GET -> rootPath / "conversionKPIs" / kpiName asAuthed (u) =>
      conversionKPIDAO.find(kpiName).flatMap { ko =>
        if (ko.isDefined)
          Ok(conversionKPIForm(ko, u))
        else NotFound(s"Cannot find the Conversion KPI under the name $kpiName")
      }
    case GET -> rootPath / "conversionKPIs" / kpiName / "delete" asAuthed (_) =>
      conversionKPIDAO.remove(kpiName) >>
        Ok(redirect(reverseRoutes.analysis, s"$kpiName, if existed, is deldeted."))

    case se @ POST -> rootPath / "conversionKPIs" asAuthed u =>
      se.request
        .as[ConversionKPI]
        .redeemWith(
          e => BadRequest(errorMsg(e.getMessage)),
          kpi =>
            conversionKPIDAO.upsert(kpi.copy(author = u.username)) >>
              Ok(
                redirect(
                  reverseRoutes.analysis,
                  s"Conversion KPI ${kpi.name} successfully saved"
                )
              )
        )
  }

  val routes = readonlyRoutes <+> managingRoutes

}

object UI {
  object Decoders {

    import CommonFormDecoders._
    implicit val messageQueryDecoder: FormDataDecoder[MessageQuery] = (
      fieldOptional[String]("description"),
      list[(FieldName, FieldValue)]("criteria")
    ).mapN(MessageQuery.apply)

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
