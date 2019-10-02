package cats.effect.scalatest

import cats.Functor
import cats.effect.Sync
import org.scalatest.{Assertion, Assertions, Succeeded}
import cats.implicits._

/**
  * Copied from FS2
  * https://github.com/functional-streams-for-scala/fs2/blob/188a37883d7bbdf22bc4235a3a1223b14dc10b6c/core/shared/src/test/scala/fs2/Fs2Spec.scala#L73-L120
  */
trait AssertingSyntax {
  self: Assertions =>

  /** Provides various ways to make test assertions on an `F[A]`. */
  implicit class Asserting[F[_], A](private val self: F[A]) {

    /**
      * Asserts that the `F[A]` completes with an `A` which passes the supplied function.
      *
      * @example {{{
      * IO(1).asserting(_ shouldBe 1)
      * }}}
      */
    def asserting(f: A => Assertion)(implicit F: Sync[F]): F[Assertion] =
      self.flatMap(a => F.delay(f(a)))

    /**
      * Asserts that the `F[A]` completes with an `A` and no exception is thrown.
      */
    def assertNoException(implicit F: Functor[F]): F[Assertion] =
      self.as(Succeeded)

    /**
      * Asserts that the `F[A]` fails with an exception of type `E`.
      */
    def assertThrows[E <: Throwable](
        implicit F: Sync[F],
        ct: reflect.ClassTag[E]
      ): F[Assertion] =
      self.attempt.flatMap {
        case Left(t: E) => F.pure(Succeeded: Assertion)
        case Left(t) =>
          F.delay(
            fail(
              s"Expected an exception of type ${ct.runtimeClass.getName} but got an exception: $t"
            )
          )
        case Right(a) =>
          F.delay(
            fail(
              s"Expected an exception of type ${ct.runtimeClass.getName} but got a result: $a"
            )
          )
      }
  }
}
