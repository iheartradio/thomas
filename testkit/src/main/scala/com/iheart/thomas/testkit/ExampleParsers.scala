package com.iheart.thomas.testkit

import cats.{Applicative, MonadThrow}
import cats.implicits._
import com.iheart.thomas.{ArmName, FeatureName}
import com.iheart.thomas.stream.{ArmParser, TimeStampParser}
import org.typelevel.jawn.ast.JValue

object ExampleParsers {
  import com.iheart.thomas.stream.JValueSyntax._
  implicit def armParser[F[_]: Applicative]: ArmParser[F, JValue] =
    new ArmParser[F, JValue] {
      def parse(
          m: JValue,
          feature: FeatureName
        ): F[Option[ArmName]] =
        m.getPath(s"treatment-groups.$feature").getString.pure[F]
    }

  implicit def timeStampParser[F[_]: MonadThrow]: TimeStampParser[F, JValue] =
    TimeStampParser.fromField[F]("timeStamp")
}
