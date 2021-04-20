package com.iheart.thomas
package testkit

import cats.effect.{ExitCode, IO, IOApp, Resource}

import java.time.OffsetDateTime
import com.iheart.thomas.abtest.model._
import com.iheart.thomas.admin.Role
import com.iheart.thomas.analysis.bayesian.models._
import com.iheart.thomas.analysis.{
  ConversionKPI,
  ConversionMessageQuery,
  Criteria,
  KPIName,
  MessageQuery
}
import com.iheart.thomas.dynamo.{AnalysisDAOs, ScanamoManagement}
import com.iheart.thomas.http4s.MongoResources
import ThrowableExtension._
import cats.implicits._

import scala.util.Random

object Factory extends IOApp {
  val now = OffsetDateTime.now

  def fakeAb(
      start: Int = 0,
      end: Int = 100,
      feature: String = "AMakeUpFeature" + Random.alphanumeric.take(5).mkString,
      alternativeIdName: Option[MetaFieldName] = None,
      groups: List[Group] = List(Group("A", 0.5, None), Group("B", 0.5, None)),
      userMetaCriteria: UserMetaCriteria = None,
      segRanges: List[GroupRange] = Nil,
      requiredTags: List[Tag] = Nil
    ): AbtestSpec =
    AbtestSpec(
      name = "test",
      author = "kai",
      feature = feature,
      start = now.plusDays(start.toLong),
      end = Some(now.plusDays(end.toLong)),
      groups = groups,
      alternativeIdName = alternativeIdName,
      userMetaCriteria = userMetaCriteria,
      segmentRanges = segRanges,
      requiredTags = requiredTags
    )

  def insertDevelopmentData: IO[Unit] = {
    LocalDynamo
      .client[IO]()
      .flatMap { implicit l =>
        implicit val ex = executionContext
        for {
          _ <- Resource.liftF(
            ScanamoManagement.ensureTables[IO](Resources.tables, 1, 1)
          )
          abtestAlg <- MongoResources.abtestAlg[IO](None)
          authAlg <- Resources.authAlg
        } yield (abtestAlg, authAlg, AnalysisDAOs.conversionKPIRepo[IO])
      }
      .use {
        case (abtestAlg, authAlg, cKpiAlg) =>
          List(
            abtestAlg.create(fakeAb(feature = "A_Feature")).void,
            authAlg.register("admin", "123456", Role.Admin).void,
            cKpiAlg
              .create(
                ConversionKPI(
                  KPIName("A_KPI"),
                  "Kai",
                  None,
                  BetaModel(2d, 2d),
                  Some(
                    ConversionMessageQuery(
                      initMessage = MessageQuery(
                        None,
                        List(Criteria("page_shown", "front_page"))
                      ),
                      convertedMessage = MessageQuery(
                        None,
                        List(Criteria("click", "front_page_recommendation"))
                      )
                    )
                  )
                )
              )
              .void
          ).map(_.handleErrorWith { e =>
              IO.delay(println(s"Failed to create data due to ${e.fullStackTrace}"))
            })
            .parSequence_

      }

  }

  def run(args: List[String]): IO[ExitCode] =
    insertDevelopmentData.as(ExitCode.Success)
}
