package com.iheart.thomas.stream

import cats.effect.ConcurrentEffect
import fs2.{Pipe, Stream}
import com.iheart.thomas.FeatureName
import com.iheart.thomas.analysis.{Conversions, KPIName}
import cats.implicits._
import com.iheart.thomas.bandit.`package`.ArmName
import com.iheart.thomas.bandit.bayesian.{BayesianMAB, ConversionBMABAlg}
import com.iheart.thomas.bandit.tracking.{Event, EventLogger}
import cats.effect.Timer

import scala.concurrent.duration.FiniteDuration

object ConversionBanditUpdater {
  type ConversionEvent = Boolean
  val Converted = true
  val Viewed = false
  type ConversionBandits = Vector[BayesianMAB[Conversions]]

  /**
    *
    * @param name
    * @param chunkSize
    * @param numOfChunksPerReallocate
    * @param historyRetention
    */
  case class Config(
      chunkSize: Int,
      numOfChunksPerReallocate: Int,
      historyRetention: Option[FiniteDuration] = None,
      checkRunningBanditsEvery: FiniteDuration)

  def updatePipes[F[_]: Timer: ConcurrentEffect, I](
      name: String,
      cfg: Config,
      toEvent: (FeatureName, KPIName) => F[Pipe[F, I, (ArmName, ConversionEvent)]]
    )(implicit
      cbm: ConversionBMABAlg[F],
      log: EventLogger[F]
    ): Stream[F, Pipe[F, I, Unit]] = {
    import cfg._
    val changingRunningBandit = ((Stream.emit[F, Unit](()) ++ Stream
      .fixedDelay[F](checkRunningBanditsEvery))
      .evalMap(_ => cbm.runningBandits()))
      .scan(
        (
          Vector.empty[BayesianMAB[Conversions]],
          none[Vector[BayesianMAB[Conversions]]]
        )
      ) { (memo, current) =>
        val old = memo._1

        def banditIdentifier(b: BayesianMAB[_]) =
          (b.feature, b.kpiName, b.abtest.data.groups.map(_.name))

        (
          current,
          if (current.map(banditIdentifier) == old.map(banditIdentifier)) None
          else Some(current)
        )
      }
      .mapFilter(_._2)
      .evalTap(
        b =>
          log(
            Event.BanditKPIUpdate
              .NewSetOfRunningBanditsDetected(b.map(_.feature))
          )
      )

    def updateConversion(
        featureName: FeatureName
      ): Pipe[F, (ArmName, ConversionEvent), Unit] =
      toConversion[F](chunkSize) andThen {
        _.broadcastTo[F](
          (i: Stream[F, Map[ArmName, Conversions]]) =>
            i.evalMap { r =>
              log.debug(s"Updating reward $r to bandit $featureName by $name") *>
                cbm
                  .updateRewardState(featureName, r)
                  .void
            },
          (i: Stream[F, Map[ArmName, Conversions]]) =>
            i.chunkN(numOfChunksPerReallocate).evalMap { _ =>
              cbm.reallocate(featureName, historyRetention).void
            }
        )
      }

    changingRunningBandit.map { bandits =>
      val updatePipes =
        log.debug(s"updating KPI state for ${bandits.map(_.feature)}") *>
          bandits
            .traverse { b =>
              toEvent(b.feature, b.kpiName)
                .map(_ andThen updateConversion(b.feature))
            }

      { input: Stream[F, I] =>
        Stream
          .eval(updatePipes)
          .flatMap { featurePipes =>
            if (featurePipes.nonEmpty)
              input.broadcastThrough(featurePipes: _*)
            else
              input.void
          }
      }
    }
  }

  private[stream] def toConversion[F[_]](
      chunkSize: Int
    ): Pipe[F, (ArmName, ConversionEvent), Map[
    ArmName,
    Conversions
  ]] = { input =>
    input
      .chunkN(chunkSize, true)
      .map { chunk =>
        val isConverted = identity[ConversionEvent] _
        (chunk
          .foldMap {
            case (an, ce) =>
              Map(an -> List(ce))
          })
          .map {
            case (an, ces) =>
              val convertedCount = ces.count(isConverted)
              (
                an,
                Conversions(
                  converted = convertedCount.toLong,
                  total = ces.size.toLong
                )
              )
          }
      }
  }
}
