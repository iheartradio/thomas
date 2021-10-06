package com.iheart.thomas
package stream

import cats.data.NonEmptyChain
import cats.MonadThrow
import cats.effect.Temporal
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
  EventsQueriedForFeature,
  MessagesParseError
}
import com.iheart.thomas.tracking.EventLogger

import java.time.Instant
import scala.util.control.NoStackTrace

trait KPIEventSource[F[_], K <: KPI, Message, Event] {
  def events(k: K): Pipe[F, Message, Event]
  def events(
      k: K,
      feature: FeatureName
    ): Pipe[F, Message, ArmKPIEvents[Event]]
}

object KPIEventSource {
  def nullSource[
      F[_],
      K <: KPI,
      Message,
      Event
    ]: KPIEventSource[F, K, Message, Event] =
    new KPIEventSource[F, K, Message, Event] {
      def events(k: K): Pipe[F, Message, Event] = _ => Stream.empty
      def events(
          k: K,
          feature: FeatureName
        ): Pipe[F, Message, ArmKPIEvents[Event]] = _ => Stream.empty
    }

  implicit def fromParsers[F[_]: MonadThrow, K <: KPI, Message, Event](
      implicit eventParser: KpiEventParser[F, Message, Event, K],
      armParser: ArmParser[F, Message],
      logger: EventLogger[F],
      timeStampParser: TimeStampParser[F, Message]
    ): KPIEventSource[F, K, Message, Event] =
    new KPIEventSource[F, K, Message, Event] {
      def events(k: K): Pipe[F, Message, Event] = {
        val parser = eventParser(k)
        (_: Stream[F, Message]).evalMap(parser).flatMap(l => Stream(l: _*))
      }

      def events(
          k: K,
          feature: FeatureName
        ): Pipe[F, Message, ArmKPIEvents[Event]] = {
        val parser = eventParser(k)
        (input: Stream[F, Message]) =>
          input
            .evalMapFilter { m =>
              armParser
                .parse(m, feature)
                .flatMap { armO =>
                  armO.flatTraverse { arm =>
                    (parser(m), timeStampParser(m))
                      .mapN { (es, ts) =>
                        NonEmptyChain.fromSeq(es).map(ArmKPIEvents(arm, _, ts))
                      }
                  }
                }
                .recoverWith { case e: Throwable =>
                  logger(MessagesParseError(e, m)) *> none.pure[F]
                }
            }
      }
    }

  implicit def fromAlg[F[_]: Temporal, Message](
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
          ): Pipe[F, Message, PerUserSamples] =
          _ =>
            pulse(k)
              .evalMap { case (query, at) =>
                query(k, at).flatTap { r =>
                  logger(EventsQueried(k, r.map(_.values.length).sum))
                }
              }
              .flatMap(Stream(_: _*))

        def events(
            k: QueryAccumulativeKPI,
            feature: FeatureName
          ): Pipe[F, Message, ArmKPIEvents[PerUserSamples]] =
          _ =>
            pulse(k)
              .evalMap { case (query, at) =>
                query(k, feature, at)
                  .map(_.map { case (arm, samples) =>
                    ArmKPIEvents(arm, NonEmptyChain(samples), at)
                  })
                  .flatTap { r =>
                    logger(
                      EventsQueriedForFeature(
                        k,
                        feature,
                        r.map(ae => (ae.armName, ae.es.head.values.length))
                      )
                    )
                  }
              }
              .flatMap(l => Stream(l: _*))
      }
  }

  case class UnknownQueryName(q: QueryName)
      extends RuntimeException
      with NoStackTrace {
    override def getMessage: FeatureName = s"Cannot find query with name $q"
  }
}
