package com.iheart.thomas
package stream

import cats.Monad
import com.iheart.thomas.abtest.PerformantAssigner
import com.iheart.thomas.abtest.model.AssignmentTruthAt.{Message, Realtime}
import com.iheart.thomas.abtest.model.{Feature, UserGroupQuery}
import cats.implicits._
import utils.time._

trait ArmExtractor[F[_], Message] {
  def apply(feature: Feature, message: Message): F[Option[ArmName]]
}

object ArmExtractor {
  implicit def default[F[_]: Monad, Message](
      implicit
      userParser: UserParser[F, Message],
      timeStampParser: TimeStampParser[F, Message],
      armParser: ArmParser[F, Message],
      assigner: PerformantAssigner[F]
    ): ArmExtractor[F, Message] = (feature, message) => {
    feature.assignmentTruthAt match {
      case Realtime =>
        for {
          uid <- userParser(message)
          ts <- timeStampParser(message)
          assignments <- assigner.assign(
            UserGroupQuery(
              uid,
              at = Some(ts.toOffsetDateTimeUTC),
              features = List(feature.name)
            )
          )
        } yield assignments.get(feature.name).map(_.groupName)
      case Message => armParser(message, feature.name)
    }
  }
}
