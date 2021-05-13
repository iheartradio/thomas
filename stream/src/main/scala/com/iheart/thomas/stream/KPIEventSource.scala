package com.iheart.thomas.stream

import cats.{Monad, MonadThrow}
import cats.effect.Timer
import com.iheart.thomas.{ArmName, FeatureName, TimeUtil}
import com.iheart.thomas.analysis.{
  KPI,
  PerUserSamples,
  PerUserSamplesQuery,
  QueryAccumulativeKPI,
  AccumulativeKPIQueryRepo,
  QueryName
}
import fs2.{Pipe, Stream}
import cats.implicits._

import java.time.Instant
import scala.util.control.NoStackTrace

trait KPIEventSource[F[_], K <: KPI, Message, Event] {
  def events(k: K): Pipe[F, Message, List[Event]]
  def events(
      k: K,
      feature: FeatureName
    ): Pipe[F, Message, List[(ArmName, Event)]]
}

object KPIEventSource {
  def nullSource[
      F[_],
      K <: KPI,
      Message,
      Event
    ]: KPIEventSource[F, K, Message, Event] =
    new KPIEventSource[F, K, Message, Event] {
      def events(k: K): Pipe[F, Message, List[Event]] = _ => Stream.empty
      def events(
          k: K,
          feature: FeatureName
        ): Pipe[F, Message, List[(ArmName, Event)]] = _ => Stream.empty
    }

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

  implicit def fromAlg[F[_]: MonadThrow: Timer, Message](
      implicit alg: AccumulativeKPIQueryRepo[F]
    ): KPIEventSource[F, QueryAccumulativeKPI, Message, PerUserSamples] = {
    if (!alg.implemented) KPIEventSource.nullSource
    else
      new KPIEventSource[F, QueryAccumulativeKPI, Message, PerUserSamples] {

        def pulse(
            k: QueryAccumulativeKPI
          ): Stream[F, (PerUserSamplesQuery[F], Instant)] =
          Stream
            .eval(
              alg
                .findQuery(k.queryName)
                .flatMap(_.liftTo[F](UnknownQueryName(k.queryName)))
            )
            .flatMap { query =>
              Stream
                .awakeEvery[F](query.frequency)
                .evalMap(_ =>
                  TimeUtil
                    .now[F]
                    .map((query, _))
                )
            }

        def events(
            k: QueryAccumulativeKPI
          ): Pipe[F, Message, List[PerUserSamples]] =
          _ => pulse(k).evalMap { case (query, now) => query(k, now) }

        def events(
            k: QueryAccumulativeKPI,
            feature: FeatureName
          ): Pipe[F, Message, List[(ArmName, PerUserSamples)]] =
          _ => pulse(k).evalMap { case (query, now) => query(k, feature, now) }
      }
  }

  case class UnknownQueryName(q: QueryName)
      extends RuntimeException
      with NoStackTrace
}
