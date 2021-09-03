package com.iheart.thomas
package kafka

import com.iheart.thomas.FeatureName
import com.iheart.thomas.analysis.KPIName

import com.iheart.thomas.analysis.ConversionEvent
import fs2.Pipe
import fs2.kafka.RecordDeserializer

trait MessageProcessor[F[_]] {
  type RawMessage
  type PreprocessedMessage

  implicit def deserializer: RecordDeserializer[F, RawMessage]
  def preprocessor: Pipe[F, RawMessage, PreprocessedMessage]
  def toConversionEvent(
      featureName: FeatureName,
      KPIName: KPIName
    ): F[
    Pipe[F, PreprocessedMessage, (ArmName, ConversionEvent)]
  ]
}

object MessageProcessor {
  def apply[F[_], Message](
      toEvent: (
          FeatureName,
          KPIName
      ) => F[Pipe[F, Message, (ArmName, ConversionEvent)]]
    )(implicit ev: RecordDeserializer[F, Message]
    ): MessageProcessor[F] {
    type RawMessage = Message;
    type PreprocessedMessage = Message
  } =
    new MessageProcessor[F] {

      type RawMessage = Message
      type PreprocessedMessage = Message
      implicit def deserializer: RecordDeserializer[F, Message] = ev
      def preprocessor: Pipe[F, Message, Message] = identity

      def toConversionEvent(
          featureName: FeatureName,
          kpiName: KPIName
        ): F[Pipe[F, Message, (ArmName, ConversionEvent)]] =
        toEvent(featureName, kpiName)
    }
}
