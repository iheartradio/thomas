package com.iheart.thomas
package cli

import cats.data.Validated
import cats.effect.ConcurrentEffect
import com.monovore.decline._
import cats.implicits._
import com.iheart.thomas.Error.CannotToChangePastTest
import com.iheart.thomas.model.TestId
import play.api.libs.json.JsObject
import play.api.libs.json.Json.{parse, prettyPrint, toJson}

import scala.util.Try
import scala.util.control.NoStackTrace

class GroupMetaCommands[F[_]](implicit F: ConcurrentEffect[F]) {

  val tidOpts = Opts.option[String]("id", "test id", "i").orNone
  val fnOpts = Opts.option[String]("feature", "test feature", "f").orNone

  val fidOrFnOps: Opts[F[Either[String, String]]] = (tidOpts, fnOpts).mapN { (tidO, fO) =>
    (tidO, fO) match {
      case (Some(_), Some(_)) =>
        F.raiseError(InvalidOptions("Cannot set both test id and test feature"))
      case (None, None) =>
        F.raiseError(InvalidOptions("Must set either test id and test feature"))
      case (Some(tid), None) =>
        tid.asLeft[String].pure[F]
      case (None, Some(f)) =>
        f.asRight[String].pure[F]
    }
  }


  val showCommand = Command(
    "show", "show group metas"
  ) {
    ( fidOrFnOps,
      HttpClientOpts.opts[F]).mapN { (tidOrFeature, client) =>

      tidOrFeature.flatMap { tidOrF =>
        client.use { c =>
          def getGMForTest(tid: TestId) = {
            c.getGroupMeta(tid).flatMap { r =>
              F.delay(println(
                s"""
                   |Group Meta:
                   | ${prettyPrint(toJson(r))}
           """.stripMargin))
            }
          }

          tidOrF.fold(getGMForTest,
            f => for {
              t <- c.featureLatestTest(f)
              _ <- F.delay(println(s"Latest test under $f is ${t._id.value} \nstart: ${t.data.start}"))
              r <- getGMForTest(t._id)
            } yield r
          )
        }
      }
    }
  }

  val addCommand = Command(
    "add", "add group metas"
  ) { (fidOrFnOps,
      Opts.option[String]("groupMeta", "json for group meta").mapValidated{ s =>
        Validated.fromTry(Try(parse(s).as[JsObject])).leftMap(_.getMessage).toValidatedNel
      },
      Opts.flag("new", "create a new revision if the current one is already started").orFalse,
      HttpClientOpts.opts[F]).mapN { (tidOrFeature, gm, nt, client) =>

      tidOrFeature.flatMap { tidOrF =>
        client.use { c =>
          tidOrF.fold(c.addGroupMeta(_, gm, nt).void,
            f => for {
              t <- c.featureLatestTest(f)
              r <- if(!t.data.canChange && !nt)
                     F.delay(println("The latest test is already started, if you want to automatically create a new revision, please run the command again with --new flag"))
                   else
                     c.addGroupMeta(t._id, gm, nt).void
            } yield r
          )
        }
      }
    }
  }

  val groupMetaCommand = Command(
    "groupMeta", "managing group meta"
  ) {Opts.subcommand(showCommand) orElse Opts.subcommand(addCommand)}


}


case class InvalidOptions(override val getMessage: String) extends RuntimeException with NoStackTrace
