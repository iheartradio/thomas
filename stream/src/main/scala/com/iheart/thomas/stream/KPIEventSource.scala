package com.iheart.thomas.stream

import cats.Monad
import com.iheart.thomas.{ArmName, FeatureName}
import com.iheart.thomas.analysis.KPI
import fs2.{Pipe, Stream}
import cats.implicits._

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
    ) =
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

}
