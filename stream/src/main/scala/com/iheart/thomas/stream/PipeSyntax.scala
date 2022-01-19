package com.iheart.thomas.stream

import fs2.Pipe
import cats.syntax.all._
object PipeSyntax {
  implicit class pipeSyntax[F[_], A, B](private val self: Pipe[F, A, B])
      extends AnyVal {

    def void: Pipe[F, A, Unit] = self.andThen(_.void)
  }
}
