package com.iheart.thomas
package http4s
package analysis

import cats.effect.Async
import com.iheart.thomas.analysis.{
  AllKPIRepo,
  ConversionKPI,
  ConversionMessageQuery,
  Criteria,
  KPIName,
  KPIRepo,
  KPIStats,
  MessageQuery,
  bayesian
}
import bayesian.models._
import com.iheart.thomas.http4s.{AuthImp, ReverseRoutes}
import com.iheart.thomas.http4s.auth.{AuthedEndpointsUtils}
import org.http4s.dsl.Http4sDsl
import cats.implicits._
import org.http4s.twirl._
import com.iheart.thomas.analysis.html._
import com.iheart.thomas.html.{errorMsg, redirect}
import org.http4s.FormDataDecoder
import FormDataDecoder._
import com.iheart.thomas.analysis.bayesian.{KPIEvaluator}
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.Key
import com.iheart.thomas.analysis.monitor.{
  AllExperimentKPIStateRepo,
  ExperimentKPIState
}
import com.iheart.thomas.http4s.AdminUI.AdminUIConfig
import com.iheart.thomas.http4s.analysis.UI.{
  MonitorInfo,
  StartMonitorRequest,
  controlArm,
  includedArms
}
import com.iheart.thomas.stream.{JobAlg, JobInfo}
import com.iheart.thomas.stream.JobSpec.{
  MonitorTest,
  ProcessSettingsOptional,
  UpdateKPIPrior
}
import org.http4s.dsl.impl.{
  OptionalMultiQueryParamDecoderMatcher,
  OptionalQueryParamDecoderMatcher
}
import tsec.authentication._

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

class UI[F[_]: Async](
    implicit
    convKpiAlg: KPIRepo[F, ConversionKPI],
    allKPIRepo: AllKPIRepo[F],
    jobAlg: JobAlg[F],
    stateRepo: AllExperimentKPIStateRepo[F],
    kPIEvaluator: KPIEvaluator[F],
    aCfg: AdminUIConfig)
    extends AuthedEndpointsUtils[F, AuthImp]
    with Http4sDsl[F] {
  val reverseRoutes = ReverseRoutes(aCfg)
  import UI.Decoders._
  val rootPath = Root / "analysis"

  val readonlyRoutes = roleBasedService(admin.Authorization.readableRoles) {

    case GET -> `rootPath` / "" asAuthed (u) =>
      for {
        states <- stateRepo.all
        kpis <- convKpiAlg.all
        r <- Ok(index(states, kpis)(UIEnv(u)))
      } yield r
  }

  val abtestRoutes = roleBasedService(admin.Authorization.analysisManagerRoles) {

    case GET -> `rootPath` / "abtests" / feature / "" asAuthed (u) =>
      for {
        js <- jobAlg.monitors(feature)
        monitors <- js.traverse { j =>
          stateRepo.find(Key(feature, j.spec.kpiName)).map(MonitorInfo(j, _))
        }
        kpis <- allKPIRepo.all
        availableKpis =
          kpis.map(_.name).filter(k => js.find(_.spec.kpiName == k).isEmpty)
        r <- Ok(
          abtest(feature, monitors, availableKpis)(UIEnv(u))
        )
      } yield r

    case se @ POST -> `rootPath` / "abtests" / feature / "monitors" asAuthed (_) =>
      se.request.as[StartMonitorRequest].flatMap { r =>
        jobAlg.schedule(MonitorTest(feature, r.kpi, r.settings)) *>
          Ok(
            redirect(
              reverseRoutes.analysisOf(feature),
              s"Started monitoring $feature on ${r.kpi}"
            )
          )
      }

    case GET -> `rootPath` / "abtests" / feature / "monitors" / kpi / "stop" asAuthed (_) =>
      jobAlg.stop(MonitorTest.jobKey(feature, KPIName(kpi))) *>
        Ok(
          redirect(
            reverseRoutes.analysisOf(feature),
            s"Stopped monitoring $feature on $kpi"
          )
        )
    case GET -> `rootPath` / "abtests" / feature / "states" / kpi / "evaluate" :? controlArm(
          caO
        ) +& includedArms(arms) asAuthed (u) =>
      kPIEvaluator(
        Key(feature, KPIName(kpi)),
        caO,
        arms.toOption.filter(_.nonEmpty)
      ).flatMap { ro =>
        Ok(
          evaluation(
            feature,
            KPIName(kpi),
            ro.map(_._1).toList.flatten,
            ro.map(_._2),
            arms.toList.flatten.toSet
          )(
            UIEnv(u)
          )
        )
      }

    case GET -> `rootPath` / "abtests" / feature / "states" / kpi / "reset" asAuthed (_) =>
      stateRepo.reset(Key(feature, KPIName(kpi))) *>
        Ok(
          redirect(
            reverseRoutes.analysisOf(feature),
            s"State for $kpi has been reset"
          )
        )

  }

  val kpiManagementRoutes =
    roleBasedService(admin.Authorization.analysisManagerRoles) {
      case GET -> `rootPath` / "conversionKPI" / "new" asAuthed u =>
        Ok(newConversionKPI()(UIEnv(u)))

      case GET -> `rootPath` / "conversionKPIs" / kpiName asAuthed u =>
        convKpiAlg.find(KPIName(kpiName)).flatMap { ko =>
          ko.fold(
            NotFound(s"Cannot find the Conversion KPI under the name $kpiName")
          ) { k =>
            jobAlg
              .findInfo[UpdateKPIPrior](UpdateKPIPrior.keyOf(KPIName(kpiName)))
              .flatMap { jobO =>
                Ok(editConversionKPI(k, jobO)(UIEnv(u)))
              }
          }
        }

      case GET -> `rootPath` / "conversionKPIs" / kpiName / "delete" asAuthed _ =>
        convKpiAlg.remove(KPIName(kpiName)) >>
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

      case se @ POST -> `rootPath` / "kpis" / kpiName / "update-prior" asAuthed _ =>
        se.request.as[ProcessSettingsOptional].flatMap { r =>
          jobAlg
            .schedule(UpdateKPIPrior(KPIName(kpiName), r))
            .flatMap { jo =>
              jo.fold(
                BadRequest(errorMsg("It's being updated right now"))
              )(_ =>
                Ok(
                  redirect(
                    reverseRoutes.convKpi(KPIName(kpiName)),
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

  object includedArms
      extends OptionalMultiQueryParamDecoderMatcher[GroupName]("includedArms")

  case class StartMonitorRequest(
      kpi: KPIName,
      settings: ProcessSettingsOptional)

  case class MonitorInfo(
      job: JobInfo[MonitorTest],
      state: Option[ExperimentKPIState[KPIStats]]) {
    def kpi: KPIName = job.spec.kpiName
  }

  object Decoders {

    import CommonFormDecoders._

    implicit val criteriaQueryDecoder: FormDataDecoder[Criteria] = (
      field[String]("fieldName"),
      field[String]("matchingRegex")
    ).mapN(Criteria.apply)

    implicit val messageQueryDecoder: FormDataDecoder[MessageQuery] = (
      fieldOptional[String]("description"),
      list[Criteria]("criteria")
    ).mapN(MessageQuery.apply)

    implicit val processSettingsDecoder: FormDataDecoder[ProcessSettingsOptional] = (
      fieldOptional[FiniteDuration]("frequency"),
      fieldOptional[Int]("eventChunkSize"),
      fieldOptional[Instant]("expiration")
    ).mapN(ProcessSettingsOptional.apply)

    implicit val smrDecoder: FormDataDecoder[StartMonitorRequest] =
      (field[KPIName]("kpi"), nested[ProcessSettingsOptional]("settings"))
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
