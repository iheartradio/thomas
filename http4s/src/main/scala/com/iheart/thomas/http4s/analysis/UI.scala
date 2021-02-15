package com.iheart.thomas
package http4s
package analysis

import cats.effect.Async
import com.iheart.thomas.analysis.{
  BetaModel,
  ConversionKPI,
  ConversionKPIAlg,
  ConversionMessageQuery,
  Criteria,
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
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.Key
import com.iheart.thomas.analysis.monitor.{ExperimentKPIState, MonitorAlg}
import com.iheart.thomas.http4s.AdminUI.AdminUIConfig
import com.iheart.thomas.http4s.analysis.UI.{
  MonitorInfo,
  StartMonitorRequest,
  UpdateKPIRequest,
  controlArm
}
import com.iheart.thomas.stream.{JobAlg, JobInfo}
import com.iheart.thomas.stream.JobSpec.{MonitorTest, UpdateKPIPrior}
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import tsec.authentication._

import java.time.{Instant, OffsetDateTime}

class UI[F[_]: Async](
    implicit
    convKpiAlg: ConversionKPIAlg[F],
    jobAlg: JobAlg[F],
    monitorAlg: MonitorAlg[F],
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

  val abtestRoutes = roleBasedService(admin.Authorization.analysisManagerRoles) {

    case GET -> `rootPath` / "abtests" / feature / "" asAuthed (u) =>
      for {
        js <- jobAlg.monitors(feature)
        monitors <- js.traverse { j =>
          monitorAlg.getConversion(Key(feature, j.spec.kpi)).map(MonitorInfo(j, _))
        }
        kpis <- convKpiAlg.all
        availableKpis =
          kpis.map(_.name).filter(k => js.find(_.spec.kpi == k).isEmpty)
        r <- Ok(
          abtest(feature, monitors, availableKpis)(UIEnv(u))
        )
      } yield r

    case se @ POST -> `rootPath` / "abtests" / feature / "monitors" asAuthed (u) =>
      se.request.as[StartMonitorRequest].flatMap { r =>
        jobAlg.schedule(MonitorTest(feature, r.kpi, r.until.toInstant)) *>
          Ok(
            redirect(
              reverseRoutes.analysisOf(feature),
              s"Started monitoring $feature on ${r.kpi}"
            )
          )
      }

    case GET -> `rootPath` / "abtests" / feature / "monitors" / kpi / "stop" asAuthed (u) =>
      jobAlg.stop(MonitorTest.jobKey(feature, KPIName(kpi))) *>
        Ok(
          redirect(
            reverseRoutes.analysisOf(feature),
            s"Stopped monitoring $feature on $kpi"
          )
        )
    case GET -> `rootPath` / "abtests" / feature / "states" / kpi / "evaluate" :? controlArm(
          caO
        ) asAuthed (u) =>
      monitorAlg.getConversion(Key(feature, KPIName(kpi))).flatMap { stateO =>
        stateO.traverse(monitorAlg.evaluate(_, caO)).flatMap { evaluationO =>
          Ok(
            evaluation(feature, KPIName(kpi), evaluationO.toList.flatten, stateO)(
              UIEnv(u)
            )
          )
        }
      }

  }

  val kpiManagementRoutes =
    roleBasedService(admin.Authorization.analysisManagerRoles) {
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

  val routes = readonlyRoutes <+> kpiManagementRoutes <+> abtestRoutes

}

object UI {
  object controlArm extends OptionalQueryParamDecoderMatcher[GroupName]("controlArm")
  case class UpdateKPIRequest(until: OffsetDateTime)
  case class StartMonitorRequest(
      kpi: KPIName,
      until: OffsetDateTime)

  case class MonitorInfo[R](
      job: JobInfo[MonitorTest],
      state: Option[ExperimentKPIState[R]]) {
    def kpi: KPIName = job.spec.kpi
  }

  object Decoders {

    import CommonFormDecoders._

    implicit val criteriaQueryDecoder: FormDataDecoder[Criteria] = (
      field[String]("fieldName"),
      field[String]("matchingValue")
    ).mapN(Criteria.apply)

    implicit val messageQueryDecoder: FormDataDecoder[MessageQuery] = (
      fieldOptional[String]("description"),
      list[Criteria]("criteria")
    ).mapN(MessageQuery.apply)

    implicit val uprDecoder: FormDataDecoder[UpdateKPIRequest] =
      field[OffsetDateTime]("until").map(UpdateKPIRequest(_))

    implicit val smrDecoder: FormDataDecoder[StartMonitorRequest] =
      (field[KPIName]("kpi"), field[OffsetDateTime]("until"))
        .mapN(StartMonitorRequest.apply)

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
