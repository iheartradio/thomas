package com.iheart.thomas.testkit

import cats.{Applicative, MonadThrow}
import cats.syntax.all._
import com.iheart.thomas.{ArmName, FeatureName, UserId}
import com.iheart.thomas.stream.{ArmParser, TimeStampParser, UserParser}
import org.typelevel.jawn.ast.JValue

object ExampleParsers {
  import com.iheart.thomas.stream.JValueSyntax._
  implicit def armParser[F[_]: Applicative]: ArmParser[F, JValue] =
    new ArmParser[F, JValue] {
      def apply(
          m: JValue,
          feature: FeatureName
        ): F[Option[ArmName]] =
        m.getPath(s"treatment-groups.$feature").getString.pure[F]
    }

  implicit def userParser[F[_]: Applicative]: UserParser[F, JValue] =
    new UserParser[F, JValue] {
      def apply(
          m: JValue
        ): F[Option[UserId]] =
        m.getPath(s"userId").getString.pure[F]
    }

  implicit def timeStampParser[F[_]: MonadThrow]: TimeStampParser[F, JValue] =
    TimeStampParser.fromField[F]("timeStamp")
}
