package com.iheart.thomas.testkit

import cats.Applicative
import cats.implicits._
import com.iheart.thomas.{ArmName, FeatureName}
import com.iheart.thomas.stream.ArmParser
import org.typelevel.jawn.ast.JValue

object ExampleArmParse {
  import com.iheart.thomas.stream.JValueSyntax._
  implicit def armParser[F[_]: Applicative] =
    new ArmParser[F, JValue] {
      def parseArm(
          m: JValue,
          feature: FeatureName
        ): F[Option[ArmName]] =
        m.getPath(s"treatment-groups.$feature").getString.pure[F]
    }
}
