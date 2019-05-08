package com.iheart.thomas
package cli
import cats.effect.ConcurrentEffect
import com.monovore.decline._
import cats.implicits._

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

  val groupMetaCommand = Command(
    "groupMeta", "managing group meta"
  ) {Opts.subcommand(showCommand)}

  def showCommand = Command(
    "show", "show group metas"
  ) {
    ( fidOrFnOps,
      HttpClientOpts.opts[F]).mapN { (tidOrFeature, client) =>
      import com.iheart.thomas.Error.NotFound
      import com.iheart.thomas.model.TestId
      import play.api.libs.json.Json.{prettyPrint, toJson}

      tidOrFeature.flatMap { tidOrF =>
        client.use { c =>
          def getGMForTest(tid: TestId) = {
            c.getGroupMeta(tid).map { r =>
              println(
                s"""
                   |Group Meta for $tid:
                   | ${prettyPrint(toJson(r))}
           """.stripMargin)
            }
          }

          tidOrF.fold(getGMForTest,
            f => for {
              testO <- c.featureTests(f).map(_.headOption)
              t <- testO.liftTo[F](NotFound(Some(s"No tests found under $f")))
              r <- getGMForTest(t._id)
            } yield r
          )
        }
      }
    }
  }


}


case class InvalidOptions(override val getMessage: String) extends RuntimeException with NoStackTrace
