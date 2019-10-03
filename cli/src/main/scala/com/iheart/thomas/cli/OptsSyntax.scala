package com.iheart.thomas.cli

import cats.ApplicativeError
import cats.data.{NonEmptyList, Validated}
import com.monovore.decline.Opts
import cats.implicits._
import _root_.play.api.libs.json.{JsObject, JsValue}
import _root_.play.api.libs.json.Json.parse

import scala.util.Try
import scala.util.control.NoStackTrace

object OptsSyntax {
  implicit class optsOps[A](private val self: Opts[A]) extends AnyVal {
    def either[F[_]] = new eitherPartial[F, A](self)
  }
  implicit class stringOptsOps(private val self: Opts[String]) extends AnyVal {
    def asJsObject: Opts[JsObject] = self.mapValidated { s =>
      Validated.fromTry(Try(parse(s))).leftMap(_.getMessage).toValidatedNel.andThen {
        (j: JsValue) =>
          j.validate[JsObject]
            .fold(
              s =>
                Validated.Invalid(
                  NonEmptyList.fromListUnsafe(
                    s.map(p =>
                        "groupMeta Json format error: " + p._1 + " -> " + p._2
                          .map(_.message)
                          .mkString)
                      .toList)),
              _.validNel
            )
      }
    }
  }

  private[cli] final class eitherPartial[F[_], A](val self: Opts[A]) extends AnyVal {
    def apply[B](that: Opts[B])(
        implicit F: ApplicativeError[F, Throwable]): Opts[F[Either[A, B]]] =
      (self.orNone, that.orNone).mapN { (sO, tO) =>
        (sO, tO) match {
          case (Some(_), Some(_)) =>
            F.raiseError(InvalidOptions(s"Cannot set both $self and $that"))
          case (None, None) =>
            F.raiseError(InvalidOptions(s"Must set either $self or $that"))
          case (Some(tid), None) =>
            tid.asLeft[B].pure[F]
          case (None, Some(f)) =>
            f.asRight[A].pure[F]
        }
      }

  }
}

case class InvalidOptions(override val getMessage: String)
    extends RuntimeException
    with NoStackTrace
