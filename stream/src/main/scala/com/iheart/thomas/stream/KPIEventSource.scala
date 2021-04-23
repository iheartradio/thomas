package com.iheart.thomas.stream

import cats.{FlatMap, Monad}
import cats.effect.Timer
import com.iheart.thomas.{ArmName, FeatureName, TimeUtil}
import com.iheart.thomas.analysis.{AccumulativeKPI, KPI, PerUserSamples}
import fs2.{Pipe, Stream}
import cats.implicits._
import com.iheart.thomas.stream.KPIEventQuery.PerUserSamplesQuery

trait KPIEventSource[F[_], K <: KPI, Message, Event] {
  def events(k: K): Pipe[F, Message, List[Event]]
  def events(
      k: K,
      feature: FeatureName
    ): Pipe[F, Message, List[(ArmName, Event)]]
}

object KPIEventSource {
  implicit def fromParsers[F[_]: Monad, K <: KPI, Message, Event](
      implicit eventParser: KpiEventParser[F, Message, Event, K],
      armParser: ArmParser[F, Message]
    ): KPIEventSource[F, K, Message, Event] =
    new KPIEventSource[F, K, Message, Event] {
      def events(k: K): Pipe[F, Message, List[Event]] = {
        val parser = eventParser(k)
        (_: Stream[F, Message]).evalMap(parser)
      }

      def events(
          k: K,
          feature: FeatureName
        ): Pipe[F, Message, List[(ArmName, Event)]] = {
        val parser = eventParser(k)
        (input: Stream[F, Message]) =>
          input
            .evalMap { m =>
              armParser.parseArm(m, feature).flatMap { armO =>
                armO.toList.flatTraverse { arm =>
                  parser(m).map(_.map((arm, _)))
                }
              }
            }
      }
    }

  implicit def fromQuery[F[_]: FlatMap: Timer, Message](
      implicit query: PerUserSamplesQuery[F]
    ): KPIEventSource[F, AccumulativeKPI, Message, PerUserSamples] =
    new KPIEventSource[F, AccumulativeKPI, Message, PerUserSamples] {
      def events(k: AccumulativeKPI): Pipe[F, Message, List[PerUserSamples]] =
        _ =>
          Stream
            .awakeEvery[F](k.period)
            .evalMap(_ =>
              TimeUtil
                .now[F]
                .flatMap(
                  query(k, _)
                )
            )

      def events(
          k: AccumulativeKPI,
          feature: FeatureName
        ): Pipe[F, Message, List[(ArmName, PerUserSamples)]] =
        _ =>
          Stream
            .awakeEvery[F](k.period)
            .evalMap(_ =>
              TimeUtil
                .now[F]
                .flatMap(
                  query(k, feature, _)
                )
            )
    }

}
