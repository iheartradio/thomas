package com.iheart.thomas
package http4s
package analysis

import cats.effect.Async
import com.iheart.thomas.analysis.{
  AccumulativeKPIQueryRepo,
  AllKPIRepo,
  ConversionKPI,
  ConversionMessageQuery,
  Criteria,
  KPIName,
  KPIRepo,
  KPIStats,
  MessageQuery,
  QueryAccumulativeKPI,
  QueryName,
  bayesian
}
import bayesian.models._
import com.iheart.thomas.http4s.{AuthImp, ReverseRoutes}
import com.iheart.thomas.http4s.auth.AuthedEndpointsUtils
import org.http4s.dsl.Http4sDsl
import cats.implicits._
import org.http4s.twirl._
import com.iheart.thomas.analysis.html._
import com.iheart.thomas.html.{errorMsg, redirect}
import org.http4s.FormDataDecoder
import FormDataDecoder._
import com.iheart.thomas.analysis.bayesian.KPIEvaluator
import com.iheart.thomas.analysis.monitor.ExperimentKPIState.{Key, Specialization}
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
import org.http4s.Uri.Path.Segment
import org.http4s.dsl.impl.{
  OptionalMultiQueryParamDecoderMatcher,
  OptionalQueryParamDecoderMatcher
}
import tsec.authentication._

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

class UI[F[_]: Async](
    implicit
    convKpiRepo: KPIRepo[F, ConversionKPI],
    accKpiRepo: KPIRepo[F, QueryAccumulativeKPI],
    allKPIRepo: AllKPIRepo[F],
    jobAlg: JobAlg[F],
    stateRepo: AllExperimentKPIStateRepo[F],
    queryAccumulativeKPIAlg: AccumulativeKPIQueryRepo[F],
    kPIEvaluator: KPIEvaluator[F],
    aCfg: AdminUIConfig)
    extends AuthedEndpointsUtils[F, AuthImp]
    with Http4sDsl[F] {
  val reverseRoutes = ReverseRoutes(aCfg)
  import UI.Decoders._
  val rootPath = Root / Segment("analysis")

  val readonlyRoutes = roleBasedService(admin.Authorization.readableRoles) {

    case GET -> `rootPath` / "" asAuthed (u) =>
      for {
        states <- stateRepo.all
        kpis <- allKPIRepo.all
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
    case GET -> `rootPath` / "abtests" / feature / "states" / kpi / specialization / "evaluate" :? controlArm(
          caO
        ) +& includedArms(arms) asAuthed (u) =>
      val spec = Specialization.withName(specialization)
      kPIEvaluator(
        Key(feature, KPIName(kpi), spec),
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

    //todo: this kpi key is not complete
    case GET -> `rootPath` / "abtests" / feature / "states" / kpi / "reset" asAuthed (_) =>
      stateRepo.delete(Key(feature, KPIName(kpi))) *>
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

      case GET -> `rootPath` / "accumulativeKPI" / "new" asAuthed u =>
        queryAccumulativeKPIAlg.queries.flatMap { qs =>
          Ok(newAccumulativeKPI(qs.map(_.name))(UIEnv(u)))
        }

      case GET -> `rootPath` / "kpis" / kpiName asAuthed u =>
        allKPIRepo.find(KPIName(kpiName)).flatMap { ko =>
          ko.fold(
            NotFound(s"Cannot find the KPI under the name $kpiName")
          ) { k =>
            jobAlg
              .findInfo[UpdateKPIPrior](UpdateKPIPrior.keyOf(KPIName(kpiName)))
              .flatMap { jobO =>
                k match {
                  case c: ConversionKPI => Ok(editConversionKPI(c, jobO)(UIEnv(u)))
                  case a: QueryAccumulativeKPI =>
                    Ok(editAccumulativeKPI(a, jobO)(UIEnv(u)))
                }

              }
          }
        }

      case GET -> `rootPath` / "kpis" / kpiName / "delete" asAuthed _ =>
        allKPIRepo.delete(KPIName(kpiName)) >>
          Ok(redirect(reverseRoutes.analysis, s"$kpiName, if existed, is deleted."))

      case se @ POST -> `rootPath` / "conversionKPIs" asAuthed u =>
        se.request
          .as[ConversionKPI]
          .redeemWith(
            e => BadRequest(errorMsg(e.getMessage)),
            kpi =>
              convKpiRepo.create(kpi.copy(author = u.username)) >>
                Ok(
                  redirect(
                    reverseRoutes.analysis,
                    s"Conversion KPI ${kpi.name} successfully created."
                  )
                )
          )

      case se @ POST -> `rootPath` / "accumulativeKPIs" asAuthed u =>
        se.request
          .as[QueryAccumulativeKPI]
          .redeemWith(
            e => BadRequest(errorMsg(e.getMessage)),
            kpi =>
              accKpiRepo.create(kpi.copy(author = u.username)) >>
                Ok(
                  redirect(
                    reverseRoutes.analysis,
                    s"Accumulative KPI ${kpi.name} successfully created."
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
                convKpiRepo.update(kpi.copy(author = u.username)) >>
                  Ok(
                    redirect(
                      reverseRoutes.analysis,
                      s"Conversion KPI ${kpi.name} successfully updated."
                    )
                  )
          )

      case se @ POST -> `rootPath` / "accumulativeKPIs" / kpiName asAuthed u =>
        se.request
          .as[QueryAccumulativeKPI]
          .redeemWith(
            e => BadRequest(errorMsg(e.getMessage)),
            kpi =>
              if (kpi.name.n != kpiName) {
                BadGateway("Cannot change KPI name")
              } else
                accKpiRepo.update(kpi.copy(author = u.username)) >>
                  Ok(
                    redirect(
                      reverseRoutes.analysis,
                      s"Accumulative KPI ${kpi.name} successfully updated."
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
                    reverseRoutes.kpi(KPIName(kpiName)),
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
      field[Double]("alpha"),
      field[Double]("beta")
    ).mapN(BetaModel.apply)

    implicit val logModelDecoder: FormDataDecoder[LogNormalModel] = (
      field[Double]("miu0"),
      field[Double]("n0"),
      field[Double]("alpha"),
      field[Double]("beta")
    ).mapN((miu0, n0, alpha, beta) =>
      LogNormalModel(NormalModel(miu0, n0, alpha, beta))
    )

    implicit val conversionKPIDecoder: FormDataDecoder[ConversionKPI] = (
      field[KPIName]("name"),
      field[String]("author"),
      fieldOptional[String]("description"),
      nested[BetaModel]("model"),
      nestedOptional[ConversionMessageQuery]("messageQuery")
    ).mapN(ConversionKPI.apply).sanitized

    implicit val accumulativeKPIDecoder: FormDataDecoder[QueryAccumulativeKPI] = (
      field[KPIName]("name"),
      field[String]("author"),
      fieldOptional[String]("description"),
      nested[LogNormalModel]("model"),
      field[QueryName]("queryName")
    ).mapN(QueryAccumulativeKPI(_, _, _, _, _, Map.empty)).sanitized
  }
}
