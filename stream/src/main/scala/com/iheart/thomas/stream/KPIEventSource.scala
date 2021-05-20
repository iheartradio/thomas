package com.iheart.thomas
package stream

import cats.{Monad, MonadThrow}
import cats.effect.Timer
import com.iheart.thomas.analysis.{
  AccumulativeKPIQueryRepo,
  KPI,
  PerUserSamples,
  PerUserSamplesQuery,
  QueryAccumulativeKPI,
  QueryName
}
import fs2.{Pipe, Stream}
import cats.implicits._
import com.iheart.thomas.stream.JobEvent.{
  EventQueryInitiated,
  EventsQueried,
  EventsQueriedForFeature
}
import com.iheart.thomas.tracking.EventLogger

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
              armParser.parse(m, feature).flatMap { armO =>
                armO.toList.flatTraverse { arm =>
                  parser(m).map(_.map((arm, _)))
                }
              }
            }
      }
    }

  implicit def fromAlg[F[_]: MonadThrow: Timer, Message](
      implicit alg: AccumulativeKPIQueryRepo[F],
      logger: EventLogger[F]
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
              val signalF = utils.time
                .now[F]
                .map((query, _))

              (Stream.eval(signalF) ++
                Stream
                  .awakeEvery[F](query.frequency)
                  .evalMap(_ => signalF))
                .evalTap(_ => logger(EventQueryInitiated(k)))
            }

        def events(
            k: QueryAccumulativeKPI
          ): Pipe[F, Message, List[PerUserSamples]] =
          _ =>
            pulse(k).evalMap { case (query, now) =>
              query(k, now).flatTap { r =>
                logger(EventsQueried(k, r.length))
              }
            }

        def events(
            k: QueryAccumulativeKPI,
            feature: FeatureName
          ): Pipe[F, Message, List[(ArmName, PerUserSamples)]] =
          _ =>
            pulse(k).evalMap { case (query, now) =>
              query(k, feature, now).flatTap { r =>
                logger(
                  EventsQueriedForFeature(k, feature, r.map(_.map(_.values.length)))
                )
              }
            }
      }
  }

  case class UnknownQueryName(q: QueryName)
      extends RuntimeException
      with NoStackTrace
}
