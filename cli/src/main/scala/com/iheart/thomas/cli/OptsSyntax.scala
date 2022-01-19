package com.iheart.thomas.cli

import cats.data.{NonEmptyList, Validated}
import com.monovore.decline.Opts
import cats.syntax.all._
import _root_.play.api.libs.json.{JsObject, JsValue, Reads}
import _root_.play.api.libs.json.Json.parse
import cats.data.Validated.Valid

import scala.io.Source
import scala.util.Try
import scala.util.control.NoStackTrace

object OptsSyntax {
  implicit class optsOps[A](private val self: Opts[A]) extends AnyVal {

    def either[B](that: Opts[B]): Opts[Either[A, B]] = {
      import Validated.{invalidNel => invalid}

      (self.orNone, that.orNone).tupled.mapValidated {
        case (Some(_), Some(_)) =>
          invalid(s"Cannot have both $self and $that")
        case (None, None) =>
          invalid(s"Must have either $self or $that")
        case (Some(tid), None) =>
          Valid(tid.asLeft[B])
        case (None, Some(f)) =>
          Valid(f.asRight[A])
      }
    }
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
                      "Json error: " + p._1 + " -> " + p._2
                        .map(_.message)
                        .mkString
                    ).toList
                  )
                ),
              _.validNel
            )
      }
    }

    def parseJson[A: Reads]: Opts[A] = self.mapValidated { s =>
      Validated.fromTry(Try(parse(s))).leftMap(_.getMessage).toValidatedNel.andThen {
        (j: JsValue) =>
          j.validate[A]
            .fold(
              s =>
                Validated.Invalid(
                  NonEmptyList.fromListUnsafe(
                    s.map(p =>
                      "Json error: " + p._1 + " -> " + p._2
                        .map(_.message)
                        .mkString
                    ).toList
                  )
                ),
              _.validNel
            )
      }
    }

    def readJsonFile[A: Reads]: Opts[A] =
      self
        .mapValidated { fileLocation =>
          Validated
            .fromTry(Try(Source.fromFile(fileLocation).getLines().mkString("\n")))
            .leftMap(_.getMessage)
            .toValidatedNel
        }
        .parseJson[A]
  }

}

case class InvalidOptions(override val getMessage: String)
    extends RuntimeException
    with NoStackTrace
